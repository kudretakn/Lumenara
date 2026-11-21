package dev.lumenara.tv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstDrawGateTest {
    @Test fun screenFrameReleasesUpgradeWorkWithoutReleasingPopulatedHubWork() = runTest {
        val screen = FirstDrawGate()
        val hub = FirstDrawGate()
        var upgradeStarted = false
        var maintenanceStarted = false
        launch { screen.await(); upgradeStarted = true }
        launch { hub.await(); maintenanceStarted = true }
        runCurrent()
        assertFalse(upgradeStarted)
        screen.onDraw()
        runCurrent()
        assertTrue(upgradeStarted)
        assertFalse(maintenanceStarted)
        assertFalse(hub.drawn.value)
        hub.onDraw()
        runCurrent()
        assertTrue(maintenanceStarted)
    }

    @Test fun startupWorkWaitsForDrawAndReleasesAllWaiters() = runTest {
        val gate = FirstDrawGate()
        var started = 0
        repeat(3) { launch { gate.await(); started++ } }
        runCurrent()
        assertEquals(0, started)
        assertFalse(gate.drawn.value)
        gate.onDraw()
        runCurrent()
        assertEquals(3, started)
        assertTrue(gate.drawn.value)
    }

    @Test fun repeatedDrawsAndLateSubscribersDoNotWaitAgain() = runTest {
        val gate = FirstDrawGate()
        gate.onDraw()
        gate.onDraw()
        gate.await()
        var finished = false
        launch { gate.await(); finished = true }
        runCurrent()
        assertTrue(finished)
    }

    @Test fun hubDetailsFollowTheFirstDrawAfterOneShortFrameWindow() = runTest {
        val gate = FirstDrawGate()
        var ready = false
        val observer = launch { ready = gate.detailsReady.first() }
        runCurrent()
        assertFalse(ready)
        gate.onDraw()
        runCurrent()
        assertFalse(ready)
        advanceTimeBy(15)
        runCurrent()
        assertFalse(ready)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(ready)
        observer.join()
    }

    @Test fun cancellingOneScreenDoesNotReleaseOrCancelOtherWaiters() = runTest {
        val gate = FirstDrawGate()
        val cancelled = launch { gate.await() }
        var finished = false
        launch { gate.await(); finished = true }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertFalse(finished)
        gate.onDraw()
        runCurrent()
        assertTrue(finished)
    }
}
