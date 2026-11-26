package dev.lumenara.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundWorkTest {
    @Test fun requiresContentDrawAndFifteenQuietSeconds() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        gate.foreground(true)
        runCurrent()
        advanceTimeBy(30_000)
        assertFalse(gate.idle.value)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        assertFalse(gate.idle.value)
        // A recomposition or Activity recreation must not restart the populated-frame timer.
        gate.onDraw()
        advanceTimeBy(1)
        runCurrent()
        assertTrue(gate.idle.value)
    }

    @Test fun inputPlaybackAndBackgroundingRestartTheQuietWindow() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        gate.foreground(true)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(10_000)
        gate.onInput()
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        assertFalse(gate.idle.value)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(gate.idle.value)
        gate.playing(true)
        assertFalse(gate.idle.value)
        runCurrent()
        advanceTimeBy(60_000)
        assertFalse(gate.idle.value)
        gate.playing(false)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(gate.idle.value)
        gate.foreground(false)
        assertFalse(gate.idle.value)
        runCurrent()
        advanceTimeBy(60_000)
        assertFalse(gate.idle.value)
        gate.foreground(true)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(gate.idle.value)
    }

    @Test fun importsCanRunBeforeFirstDrawAndHeavyJobsNeverOverlap() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        val queue = BackgroundWorkQueue(gate)
        val order = mutableListOf<String>()
        val maintenance = launch { queue.whenIdle { order += "upgrade" } }
        runCurrent()
        queue.run { order += "first-run-import" }
        assertEquals(listOf("first-run-import"), order)
        gate.foreground(true)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        maintenance.join()
        assertEquals(listOf("first-run-import", "upgrade"), order)

        var active = 0
        var maxActive = 0
        val jobs = (1..5).map { number -> launch {
            val work: suspend () -> Unit = {
                active++
                maxActive = maxOf(maxActive, active)
                delay(100)
                active--
            }
            if (number % 2 == 0) queue.whenIdle(work) else queue.run(work)
        } }
        jobs.joinAll()
        assertEquals(1, maxActive)
    }

    @Test fun queuedMaintenanceRechecksIdleWithoutBlockingUserWork() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        val queue = BackgroundWorkQueue(gate)
        gate.foreground(true)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        val release = CompletableDeferred<Unit>()
        val import = launch { queue.run { release.await() } }
        runCurrent()
        var ran = false
        val speculative = launch { queue.whenIdle { ran = true } }
        runCurrent()
        gate.onInput()
        release.complete(Unit)
        runCurrent()
        import.join()
        assertFalse(ran)
        // A quiet-window waiter must release the lane instead of holding it for 15 seconds.
        queue.run { }
        advanceTimeBy(15_000)
        runCurrent()
        speculative.join()
        assertTrue(ran)
    }

    @Test fun cancellationAndFailureReleaseTheLane() = runTest {
        val queue = BackgroundWorkQueue(StartupIdleGate(backgroundScope))
        val owner = launch { queue.run { awaitCancellation() } }
        runCurrent()
        var cancelledRan = false
        val cancelled = launch { queue.run { cancelledRan = true } }
        runCurrent()
        cancelled.cancelAndJoin()
        owner.cancelAndJoin()
        assertFalse(cancelledRan)
        try { queue.run { error("failed job") } } catch (_: IllegalStateException) { }
        assertEquals("next job", queue.run { "next job" })
    }

    @Test fun backgroundPublishingDoesNotNeedAnActivityAndSurvivesHomeKey() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        gate.awaitIdleOrBackground()
        gate.foreground(true)
        var published = false
        val publisher = launch { gate.awaitIdleOrBackground(); published = true }
        runCurrent()
        advanceTimeBy(60_000)
        assertFalse(published)
        gate.foreground(false)
        runCurrent()
        publisher.join()
        assertTrue(published)
    }

    @Test fun rapidLifecycleChangesCannotStrandAConflatedQuietWindow() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        gate.foreground(true)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(gate.idle.value)
        gate.foreground(false)
        gate.foreground(true)
        gate.playing(true)
        gate.playing(false)
        assertFalse(gate.idle.value)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(gate.idle.value)
    }
}
