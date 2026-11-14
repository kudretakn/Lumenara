package dev.lumenara.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.*
import dev.lumenara.data.Item
import dev.lumenara.design.Ink

@Composable
internal fun MultiView(
    model: LibraryModel,
    initial: List<Item>,
    available: List<Item>,
    full: (Item) -> Unit,
    back: () -> Unit,
) {
    var channels by remember(initial) { mutableStateOf(initial) }
    var focus by remember { mutableIntStateOf(0) }
    val active = remember { mutableStateMapOf<Int, ExoPlayer>() }
    val first = remember { FocusRequester() }
    LaunchedEffect(focus, active.toMap()) {
        active.forEach { (i, engine) -> engine.volume = if (i == focus) 1f else 0f }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(Ink.background)) {
        channels.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.weight(1f)) {
                pair.forEachIndexed { col, channel ->
                    val index = row * 2 + col
                    Surface(
                        onClick = { full(channel) },
                        modifier =
                            Modifier.weight(1f)
                                .fillMaxHeight()
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { if (it.hasFocus) focus = index }
                                .onPreviewKeyEvent {
                                    val key = it.nativeKeyEvent
                                    if (
                                        key.keyCode !in
                                            listOf(
                                                KeyEvent.KEYCODE_CHANNEL_UP,
                                                KeyEvent.KEYCODE_CHANNEL_DOWN,
                                            )
                                    )
                                        false
                                    else {
                                        if (key.action == KeyEvent.ACTION_UP) {
                                            val choices = available.filter {
                                                it.source == channel.source &&
                                                    (it.id == channel.id || it !in channels)
                                            }
                                            if (choices.size > 1) {
                                                val delta =
                                                    if (key.keyCode == KeyEvent.KEYCODE_CHANNEL_UP)
                                                        1
                                                    else -1
                                                val at = choices.indexOfFirst {
                                                    it.id == channel.id
                                                }
                                                channels =
                                                    channels.toMutableList().apply {
                                                        set(
                                                            index,
                                                            choices[
                                                                Math.floorMod(
                                                                    at + delta,
                                                                    choices.size,
                                                                )],
                                                        )
                                                    }
                                            }
                                        }
                                        true
                                    }
                                },
                    ) {
                        Column(Modifier.fillMaxSize().padding(5.dp)) {
                            Video(
                                model,
                                channel,
                                Modifier.weight(1f).fillMaxWidth(),
                                preview = true,
                                multi = true,
                                ready = { p ->
                                    if (p == null) active.remove(index)
                                    else {
                                        p.volume = if (index == focus) 1f else 0f
                                        active[index] = p
                                    }
                                },
                            )
                            Text(channel.title, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
