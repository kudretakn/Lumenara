package dev.lumenara.tv

import dev.lumenara.catalog.liveRetryDelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Wp09PlaybackWatchdogTest {
    @Test fun deadlineCancelsSuspendedInputPreparationAndWaitsForItsCleanup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var cleaned = false
            val attempt = async {
                runCatching {
                    wp09WatchPlayback(monitorStalls = true, snapshot = { Wp09PlaybackSample() },
                        onPrepared = { fail("No frame or progress") }, nowMs = { testScheduler.currentTime }) {
                        try { awaitCancellation() } finally { cleaned = true }
                    }
                }
            }
            runCurrent()
            advanceTimeBy(14_999)
            assertFalse(attempt.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            val failure = attempt.await().exceptionOrNull()
            assertTrue(failure is Wp09PlaybackTimeoutException)
            assertEquals(Wp09PlaybackPhase.PREPARING, (failure as Wp09PlaybackTimeoutException).phase)
            assertTrue(cleaned)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun tricklingOrReadyWithoutFrameOrProgressTimesOutAtFifteenSeconds() {
        for (position in listOf(null, 0L, 600_000L)) {
            val watch = Wp09PlaybackWatchdog(100, true)
            assertNull(watch.sample(100, Wp09PlaybackSample(position)))
            assertNull(watch.sample(15_099, Wp09PlaybackSample(position)))
            assertEquals(Wp09PlaybackPhase.PREPARING, watch.sample(15_100, Wp09PlaybackSample(position)))
        }
    }

    @Test fun frameOrFirstPositionAdvanceCompletesPreparation() {
        val video = Wp09PlaybackWatchdog(0, false)
        video.firstFrame()
        assertNull(video.sample(500, Wp09PlaybackSample(0)))
        assertTrue(video.prepared)
        assertNull(video.sample(60_000, Wp09PlaybackSample(0)))
        val audio = Wp09PlaybackWatchdog(0, true)
        audio.sample(0, Wp09PlaybackSample(600_000))
        assertNull(audio.sample(14_999, Wp09PlaybackSample(600_100)))
        assertTrue(audio.prepared)
    }

    @Test fun pauseSuspendsPreparationInsteadOfTimingOutOrRestartingItsBudget() {
        val watch = Wp09PlaybackWatchdog(0, true)
        watch.sample(10_000, Wp09PlaybackSample())
        watch.sample(10_000, Wp09PlaybackSample(paused = true))
        assertNull(watch.sample(90_000, Wp09PlaybackSample(paused = true)))
        assertEquals(500L, watch.nextCheckMs)
        assertNull(watch.sample(90_000, Wp09PlaybackSample()))
        assertNull(watch.sample(94_999, Wp09PlaybackSample()))
        assertEquals(Wp09PlaybackPhase.PREPARING, watch.sample(95_000, Wp09PlaybackSample()))
    }

    @Test fun rebufferSurvivesInactiveNativePlaybackAndEventChurn() {
        val watch = Wp09PlaybackWatchdog(0, true)
        watch.sample(0, Wp09PlaybackSample(0))
        watch.sample(100, Wp09PlaybackSample(100))
        assertNull(watch.sample(200, Wp09PlaybackSample(100, buffering = true)))
        assertEquals(Wp09PlaybackPhase.REBUFFERING, watch.phase)
        watch.sample(400, Wp09PlaybackSample(100, buffering = false))
        assertNull(watch.sample(8_099, Wp09PlaybackSample(100, buffering = true)))
        assertEquals(Wp09PlaybackPhase.REBUFFERING,
            watch.sample(8_100, Wp09PlaybackSample(100, buffering = true)))
    }

    @Test fun progressingPlaybackAndPauseDoNotTimeOut() {
        val watch = Wp09PlaybackWatchdog(0, true)
        watch.sample(0, Wp09PlaybackSample(0))
        watch.sample(100, Wp09PlaybackSample(100))
        assertNull(watch.sample(8_000, Wp09PlaybackSample(200)))
        watch.sample(8_100, Wp09PlaybackSample(200, paused = true))
        assertNull(watch.sample(90_000, Wp09PlaybackSample(200, paused = true)))
        watch.sample(90_000, Wp09PlaybackSample(200))
        assertNull(watch.sample(97_999, Wp09PlaybackSample(200)))
        assertEquals(Wp09PlaybackPhase.PLAYING, watch.sample(98_000, Wp09PlaybackSample(200)))
    }

    @Test fun eachRetryGetsFreshPreparationAndKeepsExistingBackoff() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L), (0..4).map { liveRetryDelay(it) })
        assertNull(liveRetryDelay(5))
        assertNull(liveRetryDelay(2, preview = true))
        repeat(5) { attempt ->
            val start = attempt * 20_000L
            val watch = Wp09PlaybackWatchdog(start, true)
            assertNull(watch.sample(start + 14_999, Wp09PlaybackSample()))
            assertEquals(Wp09PlaybackPhase.PREPARING, watch.sample(start + 15_000, Wp09PlaybackSample()))
        }
    }
}
