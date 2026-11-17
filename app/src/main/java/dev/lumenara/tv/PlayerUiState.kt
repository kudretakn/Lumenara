package dev.lumenara.tv

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Immutable values are safe to read in prefetched or pausable composition. */
internal data class PlayerUiState(
    val position: Long = 0,
    val duration: Long = 0,
    val tracks: Tracks = Tracks.EMPTY,
    val isLive: Boolean = false,
    val isSeekable: Boolean = false,
)

internal fun requireMainThread() {
    if (BuildConfig.DEBUG) check(Looper.myLooper() == Looper.getMainLooper()) {
        "Player UI snapshots must be updated on the main thread"
    }
}

internal fun readPlayerUiState(
    player: Player,
    checkThread: () -> Unit = ::requireMainThread,
): PlayerUiState {
    checkThread()
    return PlayerUiState(
        position = player.currentPosition,
        duration = player.duration,
        tracks = player.currentTracks,
        isLive = player.isCurrentMediaItemLive,
        isSeekable = player.isCurrentMediaItemSeekable,
    )
}

@Composable
internal fun rememberPlayerUiState(player: Player?, visible: Boolean = true): State<PlayerUiState> {
    // Never initialize from Player here: remember itself may run on a prefetch worker.
    val state = remember(player) { mutableStateOf(PlayerUiState()) }
    LaunchedEffect(player, visible) {
        val engine = player ?: return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
            fun refresh() { state.value = readPlayerUiState(engine) }
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { refresh() }
            }
            engine.addListener(listener)
            try {
                refresh()
                if (!visible) awaitCancellation()
                while (true) {
                    delay(500)
                    refresh()
                }
            } finally {
                engine.removeListener(listener)
            }
        }
    }
    return state
}
