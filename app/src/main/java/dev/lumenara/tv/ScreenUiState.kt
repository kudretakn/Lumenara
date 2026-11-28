package dev.lumenara.tv

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import dev.lumenara.design.LumenButton
import dev.lumenara.design.LumenType
import dev.lumenara.design.Spacing
import dev.lumenara.data.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Ready(emptyList()) is a completed empty result, never a loading sentinel. */
internal sealed interface ScreenUiState<out T> {
    data object Loading : ScreenUiState<Nothing>
    data class Ready<T>(val value: T) : ScreenUiState<T>
    // Deliberately omit provider exception messages/URLs from display state.
    data class Error(val code: String) : ScreenUiState<Nothing>
}

internal fun <T> Flow<T>.wp17ScreenStates(stage: String = "screen"): Flow<ScreenUiState<T>> =
    map<T, ScreenUiState<T>> { ScreenUiState.Ready(it) }
        .onStart { emit(ScreenUiState.Loading) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(ScreenUiState.Error(wp17FailureCode(stage, error)))
        }.distinctUntilChanged()

internal fun <T> ScreenUiState<T>.readyOrNull(): T? = when (this) {
    is ScreenUiState.Ready -> value
    else -> null
}

/** Only immutable scalar display fields; no entity, mutable collection or playback URL. */
@Immutable
internal data class Wp17PosterCard(val id: String, val title: String, val caption: String, val year: String) {
    companion object {
        fun from(item: Item) = Wp17PosterCard(item.id, item.title,
            item.cleanTitle.ifEmpty { item.title }, item.year)
    }
}

internal fun wp17FailureCode(stage: String, error: Throwable): String {
    fun String.safeCode() = filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }.take(80)
    return "wp17_${stage.safeCode()}_${error.javaClass.simpleName.safeCode()}"
}

/** The retry action is reachable by DPAD; Hub keeps its existing navigation card focus. */
@Composable
internal fun Wp17ScreenError(retry: () -> Unit, requestFocus: Boolean = true) {
    val requester = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        Text(stringResource(R.string.screen_read_error), Modifier.testTag("screen-error"), style = LumenType.body)
        LumenButton(onClick = retry, modifier = Modifier.focusRequester(requester).testTag("screen-retry")) {
            Text(stringResource(R.string.screen_retry))
        }
    }
}

/** Explicit viewport metadata survives the temporary loading grid on a detail return. */
internal data class Wp17GridReturn(val itemId: String, val index: Int, val firstVisible: Int, val offset: Int) {
    fun viewport(resolvedId: String, resolvedIndex: Int): Pair<Int, Int> =
        if (resolvedId == itemId) (firstVisible + resolvedIndex - index).coerceAtLeast(0) to offset
        else 0 to 0

    companion object {
        val Saver = listSaver<Wp17GridReturn?, Any>(
            save = { target -> target?.let { listOf(it.itemId, it.index, it.firstVisible, it.offset) } ?: emptyList() },
            restore = { if (it.isEmpty()) null else Wp17GridReturn(it[0] as String, it[1] as Int, it[2] as Int, it[3] as Int) },
        )
    }
}
