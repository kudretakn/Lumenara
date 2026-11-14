package dev.lumenara.tv

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.lumenara.catalog.*
import dev.lumenara.data.Item
import dev.lumenara.design.GlassPanel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/** Retry jobs belong to the mounted stream, and never survive a channel/engine change. */
@Stable
internal class LiveRetry(private val scope: CoroutineScope, private val preview: Boolean) {
    var count by mutableIntStateOf(0)
        private set
    var reconnecting by mutableStateOf(false)
        private set
    var generation by mutableIntStateOf(0)
        private set
    private var pending: Job? = null
    fun failed(): Boolean {
        if (pending?.isActive == true) return true
        val wait = liveRetryDelay(count, preview) ?: run { reconnecting = false; return false }
        count++
        reconnecting = true
        pending = scope.launch { delay(wait); generation++ }
        return true
    }
    fun stop() { pending?.cancel(); pending = null; reconnecting = false }
    fun playing() { stop(); count = 0 }
    fun manual() { playing(); generation++ }
    fun dispose() { pending?.cancel() }
}

@Composable
internal fun rememberLiveRetry(id: String, preview: Boolean = false): LiveRetry {
    val scope = rememberCoroutineScope()
    val retry = remember(id, preview) { LiveRetry(scope, preview) }
    DisposableEffect(retry) { onDispose { retry.dispose() } }
    return retry
}

@Composable
internal fun BoxScope.Reconnecting(retry: LiveRetry) {
    if (retry.reconnecting) GlassPanel(Modifier.align(Alignment.TopStart).padding(48.dp, 27.dp)
        .testTag("live-reconnecting")) {
        Text("Yeniden bağlanıyor… (${retry.count}/5)", Modifier.padding(12.dp))
    }
}

@Composable
internal fun liveChannels(model: LibraryModel, source: String): List<Item> {
    // Numbering always follows the unfiltered source's Tümü order, including in the mini guide.
    val rows by remember(source) {
        model.repo.library.browse(source, "LIVE", "", "", "", Int.MAX_VALUE)
    }.collectAsStateWithLifecycle(emptyList())
    return rows
}

@Composable
internal fun liveChannelNumbers(model: LibraryModel, source: String): Map<String, Int> {
    val order by remember(source) { model.repo.library.liveChannelOrder(source) }
        .collectAsStateWithLifecycle(emptyList())
    return remember(order) {
        order.groupBy { it.source }.values.flatMap { rows ->
            rows.mapIndexed { index, row -> row.id to channelNumber(null, index) }
        }.toMap()
    }
}

@Stable
internal class LiveNumberControl {
    var input by mutableStateOf(NumberInput())
        private set
    var channels by mutableStateOf<List<Item>>(emptyList())
    var current: String? = null
    var selectPrevious: () -> Unit = {}
    var selectNumber: (Item) -> Unit = {}
    fun apply(transition: NumberTransition) {
        input = transition.state
        when (val command = transition.command) {
            NumberCommand.Previous -> selectPrevious()
            is NumberCommand.Select -> resolveChannel(command.number, channels)?.let(selectNumber)
                ?: run { input = input.missing(System.currentTimeMillis()) }
            null -> Unit
        }
    }
    fun markMissing() { input = input.missing(System.currentTimeMillis()) }
    fun key(key: KeyEvent): Boolean {
        val code = key.keyCode
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
        val last = code == KeyEvent.KEYCODE_LAST_CHANNEL
        val confirm = input.digits.isNotEmpty() && code in listOf(KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        if (digit == null && !last && !confirm) return false
        if (key.action == KeyEvent.ACTION_UP) when {
            digit != null -> apply(input.digit(digit, System.currentTimeMillis()))
            last -> apply(NumberTransition(NumberInput(), NumberCommand.Previous))
            confirm -> apply(input.confirm())
        }
        return true
    }
}

@Composable
internal fun rememberLiveNumberControl(model: LibraryModel, source: String,
    currentId: String? = null, select: (Item, List<Item>) -> Unit): LiveNumberControl {
    val state = remember(source) { LiveNumberControl() }
    val allChannels = liveChannels(model, source)
    val channels = if (source.isNotEmpty()) allChannels else
        allChannels.filter { it.source == allChannels.firstOrNull()?.source }
    val scope = rememberCoroutineScope()
    val currentSelect by rememberUpdatedState(select)
    val currentIdState by rememberUpdatedState(currentId)
    SideEffect {
        state.channels = channels
        state.current = currentIdState
        state.selectPrevious = {
            scope.launch {
                val id = model.prefs.recentLive.first().firstOrNull { currentIdState != null && it != currentIdState }
                val target = id?.let { model.repo.library.liveByIds("", listOf(it)).first().firstOrNull() }
                if (target == null) state.markMissing()
                else {
                    val rows = if (target.source == source) channels else
                        model.repo.library.browse(target.source, "LIVE", "", "", "", Int.MAX_VALUE).first()
                    currentSelect(target, rows)
                }
            }
        }
        state.selectNumber = { target ->
            val rows = if (channels.any { it.id == target.id }) channels else listOf(target)
            scope.launch {
                val group = if (target.source == source) rows else
                    model.repo.library.browse(target.source, "LIVE", "", "", "", Int.MAX_VALUE).first()
                currentSelect(target, group)
            }
        }
    }
    LaunchedEffect(state, state.input.deadline, state.input.missingUntil) {
        val deadline = if (state.input.digits.isNotEmpty()) state.input.deadline else state.input.missingUntil
        if (deadline > 0) {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
            state.apply(state.input.tick(System.currentTimeMillis()))
        }
    }
    return state
}

@Composable
internal fun BoxScope.NumberEntry(state: LiveNumberControl) {
    val text = state.input.digits.ifEmpty { if (state.input.missingUntil > 0) "Kanal yok" else "" }
    if (text.isNotEmpty()) GlassPanel(Modifier.align(Alignment.TopEnd).padding(48.dp, 27.dp)
        .testTag("live-number-entry")) { Text(text, Modifier.padding(16.dp)) }
}
