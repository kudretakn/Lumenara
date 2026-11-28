package dev.lumenara.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Wp07BackgroundWorkTest {
    @Test fun continuousInputStillStartsMaintenanceByTheStartupDeadline() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        val queue = BackgroundWorkQueue(gate)
        gate.foreground(true)
        gate.onDraw()
        var startedAt: Long? = null
        val maintenance = launch {
            Wp07MaintenanceRunner(queue, { false }, { testScheduler.currentTime }) {
                startedAt = testScheduler.currentTime
                false
            }.run()
        }
        runCurrent()
        repeat(19) {
            advanceTimeBy(1_000)
            gate.onInput()
            gate.onDraw()
            runCurrent()
            assertNull(startedAt)
        }
        advanceTimeBy(999)
        runCurrent()
        assertNull(startedAt)
        advanceTimeBy(1)
        gate.onInput()
        runCurrent()
        maintenance.join()
        assertEquals(20_000L, startedAt)
    }

    @Test fun inputAfterDeadlineCancelsSpeculativeWorkWithoutClosingStartupGate() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        val queue = BackgroundWorkQueue(gate)
        gate.foreground(true)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        var active = false
        var starts = 0
        val optional = launch {
            queue.speculative {
                starts++
                active = true
                try { awaitCancellation() } finally { active = false }
            }
        }
        runCurrent()
        assertTrue(active)
        gate.onInput()
        assertTrue(gate.idle.value)
        assertFalse(gate.maintenanceIdle.value)
        runCurrent()
        assertFalse(active)
        advanceTimeBy(499)
        runCurrent()
        assertFalse(active)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(active)
        assertEquals(2, starts)
        optional.cancelAndJoin()
    }

    @Test fun slowPosterAndProviderWorkNeverHoldTheUserImportLane() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        val queue = BackgroundWorkQueue(gate)
        gate.foreground(true)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        var optionalActive = 0
        var maxActive = 0
        val optionalJobs = List(2) { launch {
            queue.speculative {
                optionalActive++
                maxActive = maxOf(maxActive, optionalActive)
                try { awaitCancellation() } finally { optionalActive-- }
            }
        } }
        runCurrent()
        assertEquals(1, optionalActive)
        var imported = false
        val user = launch { queue.run { imported = true } }
        runCurrent()
        assertTrue("Import must finish with the poster still suspended", imported)
        assertEquals(1, optionalActive)
        gate.playing(true)
        runCurrent()
        assertEquals(0, optionalActive)
        assertEquals(1, maxActive)
        user.join()
        optionalJobs.forEach { it.cancelAndJoin() }
    }

    @Test fun inputAfterTwentySecondsStopsMaintenanceWithinTheCurrentBatch() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        val queue = BackgroundWorkQueue(gate)
        gate.foreground(true)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        val release = CompletableDeferred<Unit>()
        var batches = 0
        val maintenance = launch {
            Wp07MaintenanceRunner(queue, { false }, { testScheduler.currentTime }) {
                batches++
                if (batches == 1) release.await()
                true
            }.run()
        }
        runCurrent()
        assertEquals(1, batches)
        gate.onInput()
        assertTrue(gate.idle.value)
        assertFalse(gate.maintenanceIdle.value)
        release.complete(Unit)
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertEquals("No second batch during input quiet time", 1, batches)
        repeat(3) {
            gate.onInput()
            runCurrent()
            advanceTimeBy(499)
            runCurrent()
            assertEquals(1, batches)
        }
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, batches)
        maintenance.cancelAndJoin()
    }

    @Test fun queuedMaintenanceRechecksInputAndLetsAnImportThroughAfterDeadline() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        val queue = BackgroundWorkQueue(gate)
        gate.foreground(true)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        val release = CompletableDeferred<Unit>()
        val user = launch { queue.run { release.await() } }
        runCurrent()
        var ran = false
        val maintenance = launch { queue.whenMaintenanceIdle { ran = true } }
        runCurrent()
        gate.onInput()
        release.complete(Unit)
        user.join()
        runCurrent()
        assertFalse(ran)
        queue.run { } // Quiet-window waiter must release the catalog mutex.
        advanceTimeBy(500)
        runCurrent()
        maintenance.join()
        assertTrue(ran)
    }

    @Test fun slowTransactionsReduceTheNextBatchAndNeverGrowBeyondThirtyTwoRows() {
        val budget = Wp07BatchBudget()
        assertEquals(16, budget.limit)
        budget.record(50)
        assertEquals(8, budget.limit)
        repeat(10) { budget.record(100) }
        assertEquals(1, budget.limit)
        repeat(100) { budget.record(1) }
        assertEquals(32, budget.limit)
    }
}
