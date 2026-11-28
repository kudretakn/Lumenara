package dev.lumenara.player

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Wp14SubtitleClockTest {
    @Test fun noSelectedTextHasNoPeriodicScansAndEventsRenderOnce() = runTest {
        val events = MutableStateFlow(Wp14SubtitleClockState(false, true))
        var scans = 0
        val job = backgroundScope.launch { wp14SubtitleClock(events) { scans++ } }
        runCurrent()
        assertEquals(1, scans)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, scans)
        events.value = events.value.copy(revision = 1)
        runCurrent()
        assertEquals(2, scans)
        job.cancel()
    }

    @Test fun disablingTextAndPausingEachStopTheFiftyMillisecondClock() = runTest {
        val events = MutableStateFlow(Wp14SubtitleClockState(true, true))
        var scans = 0
        val job = backgroundScope.launch { wp14SubtitleClock(events) { scans++ } }
        runCurrent()
        advanceTimeBy(201)
        runCurrent()
        assertEquals(5, scans)
        events.value = Wp14SubtitleClockState(true, false)
        runCurrent()
        val pausedScans = scans
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(pausedScans, scans)
        events.value = Wp14SubtitleClockState(false, true)
        runCurrent()
        val disabledScans = scans
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(disabledScans, scans)
        job.cancel()
    }

    @Test fun delayEventsRevisitAndAdvanceCuesAtTheSamePausedPosition() = runTest {
        val timeline = SubtitleTimeline<String>(false)
        timeline.add(0, 2_000_000, listOf("first"))
        timeline.add(3_000_000, 4_000_000, listOf("second"))
        timeline.add(5_000_000, 8_000_000, listOf("third"))
        val events = MutableStateFlow(Wp14SubtitleClockState(true, false))
        val position = 3_500_000L
        var delay = 0L
        var displayed = emptyList<String>()
        val job = backgroundScope.launch {
            wp14SubtitleClock(events) { displayed = timeline.at(position - delay) }
        }
        runCurrent()
        assertEquals(listOf("second"), displayed)
        delay = 3_000_000
        events.value = events.value.copy(revision = 1)
        runCurrent()
        assertEquals(listOf("first"), displayed)
        delay = -3_000_000
        events.value = events.value.copy(revision = 2)
        runCurrent()
        assertEquals(listOf("third"), displayed)
        job.cancel()
    }

    @Test fun outputPublishesDecoderChangesAndClampsDelayWithoutAdvancingPosition() {
        val delay = AtomicLong()
        val output = SubtitleShiftOutput(delay)
        val initial = output.changes.value
        output.add(0, 2_000_000, emptyList())
        assertEquals(initial + 1, output.changes.value)
        output.render(1_000_000)
        delay.set(50_000_000)
        output.render()
        assertEquals(10_000_000L, output.appliedDelayUs)
        delay.set(-50_000_000)
        output.render()
        assertEquals(-10_000_000L, output.appliedDelayUs)
        output.clear()
        assertEquals(initial + 2, output.changes.value)
    }

    @Test fun decoderArrivalWhilePausedRendersWithoutAPeriodicScan() = runTest {
        val output = SubtitleShiftOutput(AtomicLong())
        val states = MutableStateFlow(Wp14SubtitleClockState(true, false))
        val events = combine(states, output.changes) { state, revision -> state.copy(revision = revision) }
        var scans = 0
        val job = backgroundScope.launch { wp14SubtitleClock(events) { output.render(0); scans++ } }
        runCurrent()
        assertEquals(1, scans)
        output.add(0, 1_000_000, emptyList())
        runCurrent()
        assertEquals(2, scans)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, scans)
        job.cancel()
    }

    @Test fun mergedTimelinePreservesOrderingAndExclusiveEnd() {
        val timeline = SubtitleTimeline<String>(true)
        timeline.add(0, 100, listOf("long"))
        timeline.add(10, 20, listOf("short"))
        timeline.add(10, 30, listOf("same-start"))
        assertEquals(listOf("long", "short", "same-start"), timeline.at(19))
        assertEquals(listOf("long", "same-start"), timeline.at(20))
        assertEquals(listOf("long"), timeline.at(30))
    }
}
