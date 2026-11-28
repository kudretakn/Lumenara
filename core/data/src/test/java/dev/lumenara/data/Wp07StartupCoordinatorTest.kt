package dev.lumenara.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Wp07StartupCoordinatorTest {
    @Test fun recoveryCompletesBeforeAnyWaiterCanReadAndCallerCancellationDoesNotCancelRecovery() = runTest {
        val release = CompletableDeferred<Unit>()
        var pending: String? = "old settings"
        var settings = "partial settings"
        val recovery = RestoreTransaction(object : RestoreStorage {
            override suspend fun pending() = pending
            override suspend fun restoreSnapshot(snapshot: String) { release.await(); settings = snapshot }
            override suspend fun clearPending() { pending = null }
            override suspend fun snapshot() = settings
            override suspend fun savePending(snapshot: String) { pending = snapshot }
            override suspend fun <T> transaction(block: suspend () -> T) = block()
        })
        var opens = 0
        val readiness = Wp07StartupReadiness(backgroundScope) {
            opens++
            recovery.recover()
            settings
        }
        runCurrent()
        assertEquals(0, opens)
        val cancelledScreen = async { readiness.awaitReady() }
        val worker = async { readiness.awaitReady() }
        runCurrent()
        assertNull(readiness.readyOrNull)
        assertEquals(Wp07StartupState.Loading, readiness.state.value)
        assertFalse(worker.isCompleted)
        cancelledScreen.cancelAndJoin()
        release.complete(Unit)
        assertEquals("old settings", worker.await())
        assertNull(pending)
        assertEquals(1, opens)
        assertEquals("old settings", readiness.readyOrNull)
        assertEquals(Wp07StartupState.Ready, readiness.state.value)
    }

    @Test fun failedRecoveryNeverPublishesData() = runTest {
        val failure = IllegalStateException("recovery failed")
        var attempts = 0
        val readiness = Wp07StartupReadiness<String>(backgroundScope) { attempts++; throw failure }
        val result = async { runCatching { readiness.awaitReady() } }
        val otherWaiter = async { runCatching { readiness.awaitReady() } }
        for (error in listOf(result.await().exceptionOrNull(), otherWaiter.await().exceptionOrNull())) {
            assertEquals(IllegalStateException::class.java, error?.javaClass)
            assertEquals("recovery failed", error?.message)
        }
        assertNull(readiness.readyOrNull)
        val stateError = (readiness.state.value as Wp07StartupState.Error).cause
        assertEquals(IllegalStateException::class.java, stateError.javaClass)
        assertEquals("recovery failed", stateError.message)
        val laterError = runCatching { readiness.awaitReady() }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, laterError?.javaClass)
        assertEquals("recovery failed", laterError?.message)
        assertEquals(1, attempts)
        assertNull(readiness.readyOrNull)
        assertTrue(readiness.state.value is Wp07StartupState.Error)
    }

    @Test fun failedRecoveryDoesNotCancelItsOwningScope() = runTest {
        val owner = Job()
        try {
            val readiness = Wp07StartupReadiness<String>(CoroutineScope(coroutineContext + owner)) {
                error("recovery failed")
            }
            val failure = runCatching { readiness.awaitReady() }.exceptionOrNull()
            assertEquals("recovery failed", failure?.message)
            assertTrue(owner.isActive)
            val sibling = CoroutineScope(coroutineContext + owner).async { "still running" }
            assertEquals("still running", sibling.await())
        } finally {
            owner.cancel()
        }
    }

    @Test fun ownerCancellationReleasesWaitersEvenBeforeRecoveryStarts() = runTest {
        for (cancelBeforeStart in listOf(true, false)) {
            val owner = Job()
            try {
                var attempts = 0
                val readiness = Wp07StartupReadiness<String>(CoroutineScope(coroutineContext + owner)) {
                    attempts++
                    awaitCancellation()
                }
                if (cancelBeforeStart) owner.cancel()
                val waiter = async { runCatching { readiness.awaitReady() } }
                runCurrent()
                assertEquals(if (cancelBeforeStart) 0 else 1, attempts)
                owner.cancel()
                assertTrue(waiter.await().exceptionOrNull() is CancellationException)
                assertTrue(runCatching { readiness.awaitReady() }.exceptionOrNull() is CancellationException)
                assertNull(readiness.readyOrNull)
                assertTrue((readiness.state.value as Wp07StartupState.Error).cause is CancellationException)
            } finally {
                owner.cancel()
            }
        }
    }

    @Test fun overlappingActivityStartsKeepForegroundAndStartupSchedulingEligible() = runTest {
        val gate = StartupIdleGate(backgroundScope)
        var foreground = false
        val activities = Wp07StartedActivities { foreground = it; gate.foreground(it) }
        val old = Any()
        val replacement = Any()
        activities.start(old)
        gate.onDraw()
        runCurrent()
        advanceTimeBy(1_000)
        activities.start(replacement)
        activities.start(replacement) // Duplicate callback must not inflate the count.
        activities.stop(old)
        activities.stop(old)
        assertTrue(foreground)
        runCurrent()
        advanceTimeBy(14_000)
        runCurrent()
        assertTrue("Startup scheduling must open with the replacement visible", gate.idle.value)
        activities.stop(replacement)
        assertFalse(foreground)
        assertFalse(gate.idle.value)
    }

    @Test fun runningLowCriticalAndHiddenTrimOnlyAnAlreadyReadyValue() {
        for (level in listOf(10, 15, 20, 40, 60, 80)) {
            var trimmed = 0
            var cleared = 0
            wp07TrimMemory<Any>(level, { null }, { trimmed++ }, { cleared++ })
            assertEquals(0, trimmed)
            assertEquals(1, cleared)
            val ready = Any()
            wp07TrimMemory(level, { ready }, { assertSame(ready, it); trimmed++ }, { cleared++ })
            assertEquals(1, trimmed)
            assertEquals(2, cleared)
        }
        wp07TrimMemory<Any>(5, { error("No pressure: do not even peek") }, {}, {})
    }
}
