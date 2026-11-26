package dev.lumenara.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread owner. Keep the current picture until a burst settles and its last target resolves. */
class LiveZapController<T>(private val scope: CoroutineScope, private val debounceMs: Long = 80,
    private val targetId: (T) -> String? = { null }) {
    private var anchor: String? = null
    private var offset = 0
    private var selected: String? = null
    private var selectedOffset = 0
    private var generation = 0L
    private var job: Job? = null

    fun synchronize(playing: String) {
        // Observing our own selection must not drop keys pressed before recomposition.
        if (anchor == playing || selected == playing) return
        cancel()
        anchor = playing
    }

    fun move(playing: String, delta: Int, resolve: suspend (Int) -> T?, start: (T) -> Unit) {
        if (playing != anchor && playing == selected) {
            anchor = playing
            offset -= selectedOffset
            selectedOffset = 0
            selected = null
        }
        synchronize(playing)
        offset += delta
        val distance = offset
        val request = ++generation
        job?.cancel()
        job = scope.launch {
            delay(debounceMs)
            if (distance == 0 && selectedOffset == 0) return@launch
            try {
                val target = resolve(distance)
                // Also reject resolvers that finish despite cancellation.
                if (request != generation) return@launch
                if (target != null) {
                    selected = targetId(target)
                    selectedOffset = distance
                    start(target)
                } else offset = selectedOffset
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (request == generation) offset = selectedOffset
            }
        }
    }

    fun cancel() {
        generation++
        offset = 0
        selected = null
        selectedOffset = 0
        job?.cancel()
        job = null
    }
}
