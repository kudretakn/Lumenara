package dev.lumenara.tv

import dev.lumenara.catalog.SleepTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Wp14UiDeadlinesTest {
    @Test fun closedAndEpisodeEndSleepTimersHaveZeroPeriodicWrites() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val timer = SleepTimer { testScheduler.currentTime }
            var writes = 0
            var expirations = 0
            wp14SleepTimerWake(timer, { writes++ }, { expirations++ })
            timer.select(SleepTimer.Choice.EPISODE)
            wp14SleepTimerWake(timer, { writes++ }, { expirations++ })
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(0, writes)
            assertEquals(0, expirations)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun timedSleepPublishesOnlyTheFinalMinuteAndExpiresOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val timer = SleepTimer { testScheduler.currentTime }
            timer.select(SleepTimer.Choice.MINUTES_15)
            var writes = 0
            var expirations = 0
            val job = launch {
                while (timer.remainingMs() != null)
                    wp14SleepTimerWake(timer, { writes++ }, { expirations++ })
            }
            runCurrent()
            advanceTimeBy(14 * 60_000L)
            assertEquals(0, writes)
            runCurrent()
            assertEquals(1, writes)
            assertTrue(timer.showCountdown())
            advanceTimeBy(60_000)
            runCurrent()
            job.join()
            assertEquals(61, writes)
            assertEquals(1, expirations)
            assertEquals(SleepTimer.Choice.OFF, timer.choice)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun extendingTheTimerCancelsTheOldCountdownWake() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val timer = SleepTimer { testScheduler.currentTime }
            timer.select(SleepTimer.Choice.MINUTES_15)
            advanceTimeBy(14 * 60_000L)
            var writes = 0
            val previous = launch { wp14SleepTimerWake(timer, { writes++ }, { fail("Expired early") }) }
            runCurrent()
            timer.addFifteenMinutes()
            previous.cancelAndJoin()
            val replacement = launch { wp14SleepTimerWake(timer, { writes++ }, { fail("Expired early") }) }
            runCurrent()
            advanceTimeBy(14 * 60_000L)
            runCurrent()
            assertFalse(timer.showCountdown())
            assertEquals(0, writes)
            replacement.cancelAndJoin()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun bannerAndGuideDeadlinesWriteOnceAndCanBeRearmedByActivity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var writes = 0
            val banner = launch { wp14AwaitDeadline(3000, { testScheduler.currentTime }) { writes++ } }
            var guide = launch { wp14AwaitDeadline(8000, { testScheduler.currentTime }) { writes++ } }
            runCurrent()
            advanceTimeBy(2999)
            assertEquals(0, writes)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, writes)
            guide.cancelAndJoin()
            guide = launch { wp14AwaitDeadline(11_000, { testScheduler.currentTime }) { writes++ } }
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(1, writes)
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(2, writes)
            banner.join()
            guide.join()
            advanceTimeBy(60_000)
            assertEquals(2, writes)
        } finally { Dispatchers.resetMain() }
    }
}
