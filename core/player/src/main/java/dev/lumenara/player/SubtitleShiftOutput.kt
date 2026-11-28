package dev.lumenara.player

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Retains a small past/future window so changing the clock never loses consumed cues. */
@UnstableApi
class SubtitleShiftOutput(val delayUs: AtomicLong) {
    private var timeline = SubtitleTimeline<Cue>(false)
    private var output: TextOutput? = null
    private var last: List<Cue>? = null
    private var lastPositionUs = 0L
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision.asStateFlow()
    @Volatile var appliedDelayUs: Long = 0L
        private set

    @Synchronized internal fun connect(output: TextOutput) { this.output = output }

    @Synchronized internal fun clear(merge: Boolean = false) {
        timeline = SubtitleTimeline(merge)
        last = null
        revision.value++
    }

    @Synchronized internal fun add(start: Long, duration: Long, cues: List<Cue>) {
        timeline.add(start, if (duration == C.TIME_UNSET) Long.MAX_VALUE else start + duration, cues)
        revision.value++
    }

    /** Called on the application thread even while paused; no seek or player recreation. */
    @Synchronized fun render(positionUs: Long = lastPositionUs) {
        lastPositionUs = positionUs
        appliedDelayUs = delayUs.get().coerceIn(-10_000_000L, 10_000_000L)
        val time = positionUs - appliedDelayUs
        val cues = timeline.at(time)
        if (last != cues) {
            last = cues
            output?.onCues(CueGroup(cues, positionUs))
        }
        // Keep the preceding replacement (it can have an indefinite duration).
        timeline.prune(positionUs - 10_000_000L)
    }
}
