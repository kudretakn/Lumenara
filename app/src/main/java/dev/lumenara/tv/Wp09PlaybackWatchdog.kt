package dev.lumenara.tv

import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class Wp09PlaybackPhase { PREPARING, PLAYING, REBUFFERING }
internal class Wp09PlaybackTimeoutException(val phase: Wp09PlaybackPhase) : IOException("Playback timeout: $phase")

internal data class Wp09PlaybackSample(
    val positionMs: Long? = null,
    val paused: Boolean = false,
    val buffering: Boolean = false,
)

/** Monotonic active-time budgets; native isPlaying=false during buffering never resets them. */
internal class Wp09PlaybackWatchdog(startMs: Long, private val monitorStalls: Boolean) {
    var phase = Wp09PlaybackPhase.PREPARING
        private set
    @Volatile var prepared = false
        private set
    private var lastAtMs = startMs
    private var elapsedMs = 0L
    private var previousPosition: Long? = null
    private var wasPaused = false
    private var rendered = false

    val nextCheckMs: Long get() = if (wasPaused) 500L else
        ((if (prepared) 8_000L else 15_000L) - elapsedMs).coerceIn(1, 500)

    fun firstFrame() { rendered = true }

    fun sample(nowMs: Long, value: Wp09PlaybackSample): Wp09PlaybackPhase? {
        // Paused intervals do not spend either preparation or stall budgets.
        if (!wasPaused && !value.paused) elapsedMs += (nowMs - lastAtMs).coerceAtLeast(0)
        lastAtMs = nowMs
        wasPaused = value.paused
        val position = value.positionMs?.takeIf { it >= 0 }
        val advanced = position != null && previousPosition != null && position > previousPosition!!
        previousPosition = position
        if (value.paused) {
            if (prepared) elapsedMs = 0
            return null
        }
        if (phase == Wp09PlaybackPhase.PREPARING) {
            if (rendered || advanced) {
                phase = Wp09PlaybackPhase.PLAYING
                prepared = true
                elapsedMs = 0
            }
            else if (elapsedMs >= 15_000) return phase
        }
        if (!prepared || !monitorStalls) return null
        phase = if (value.buffering) Wp09PlaybackPhase.REBUFFERING else Wp09PlaybackPhase.PLAYING
        // Buffering/Playing event churn cannot buy another stall budget.
        if (advanced) elapsedMs = 0
        return phase.takeIf { elapsedMs >= 8_000 }
    }
}

/** The child fails the attempt even while its parent is suspended opening HTTP/native input. */
internal suspend fun <T> wp09WatchPlayback(
    monitorStalls: Boolean,
    snapshot: () -> Wp09PlaybackSample,
    onPrepared: () -> Unit,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
    block: suspend CoroutineScope.(Wp09PlaybackWatchdog) -> T,
): T = coroutineScope {
    val watchdog = Wp09PlaybackWatchdog(nowMs(), monitorStalls)
    val monitor = launch(Dispatchers.Main.immediate) {
        var reported = false
        while (true) {
            val timeout = watchdog.sample(nowMs(), snapshot())
            if (timeout != null) throw Wp09PlaybackTimeoutException(timeout)
            if (watchdog.prepared && !reported) { reported = true; onPrepared() }
            if (watchdog.prepared && !monitorStalls) return@launch
            delay(watchdog.nextCheckMs)
        }
    }
    try { block(watchdog) } finally { monitor.cancel() }
}
