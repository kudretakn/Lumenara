package dev.lumenara.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** Events still render while paused; only selected, playing text needs a 50 ms clock. */
data class Wp14SubtitleClockState(val selectedText: Boolean, val playing: Boolean, val revision: Long = 0)

suspend fun wp14SubtitleClock(states: Flow<Wp14SubtitleClockState>, render: () -> Unit) {
    states.collectLatest { state ->
        render()
        if (state.selectedText && state.playing) while (true) {
            delay(50)
            render()
        }
    }
}
