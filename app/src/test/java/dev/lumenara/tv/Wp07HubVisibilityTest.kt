package dev.lumenara.tv

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Wp07HubVisibilityTest {
    @Test fun hidingHubStopsAllThreeProducersImmediatelyAndReturningRestartsThem() = runTest {
        val visible = MutableStateFlow(true)
        var active = 0
        val snapshots = List(3) {
            flow {
                active++
                try { emit(1); awaitCancellation() } finally { active-- }
            }.wp07WhileVisible(visible).stateIn(backgroundScope, SharingStarted.Eagerly, 0)
        }
        runCurrent()
        assertEquals(3, active) // Preserve the initial small-read prewarm.
        assertTrue(snapshots.all { it.value == 1 })
        visible.value = false // Route change, playback, or Activity STOP.
        runCurrent()
        assertEquals(0, active)
        assertTrue("A small last snapshot may remain while hidden", snapshots.all { it.value == 1 })
        visible.value = true
        runCurrent()
        assertEquals(3, active)
    }
}
