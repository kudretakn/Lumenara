package dev.lumenara.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveZapControllerTest {
    @Test fun singlePressStartsOnlyAfterDebounce() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        zap.move("playing", 1, { it }, started::add)
        advanceTimeBy(79)
        assertTrue(started.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(1), started)
    }

    @Test fun rapidPressesAccumulateAndResolveOnlyTheLastTarget() = runTest {
        val resolved = mutableListOf<Int>()
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        repeat(5) {
            zap.move("playing", 1, { resolved.add(it); it }, started::add)
            advanceTimeBy(20)
        }
        advanceUntilIdle()
        assertEquals(listOf(5), resolved)
        assertEquals(listOf(5), started)
    }

    @Test fun directionChangesUseNetDistance() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        for (delta in listOf(1, 1, -1)) zap.move("playing", delta, { it }, started::add)
        advanceUntilIdle()
        assertEquals(listOf(1), started)
    }

    @Test fun oppositePressesKeepCurrentPlaybackWithoutResolvingOrStarting() = runTest {
        val started = mutableListOf<Int>()
        var resolved = false
        val zap = LiveZapController<Int>(this)
        for (delta in listOf(1, -1)) zap.move("playing", delta, { resolved = true; it }, started::add)
        advanceUntilIdle()
        assertTrue(started.isEmpty())
        assertEquals(false, resolved)
    }

    @Test fun newPressCancelsAnInFlightLookup() = runTest {
        var cancelled = false
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        zap.move("playing", 1, {
            try { awaitCancellation() } finally { cancelled = true }
        }, started::add)
        advanceTimeBy(80)
        runCurrent()
        zap.move("playing", 1, { it }, started::add)
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(listOf(2), started)
    }

    @Test fun lateUncooperativeLookupCannotStartSupersededChannel() = runTest {
        val finish = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        zap.move("playing", 1, { distance ->
            withContext(NonCancellable) { finish.await() }
            distance
        }, started::add)
        advanceTimeBy(80)
        runCurrent()
        zap.move("playing", 1, { it }, started::add)
        advanceTimeBy(80)
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(2), started)
    }

    @Test fun directSelectionCancelsPendingZapAndResetsItsOffset() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        zap.move("playing", 3, { it }, started::add)
        zap.synchronize("guide-selection")
        advanceUntilIdle()
        assertTrue(started.isEmpty())
        zap.move("guide-selection", -1, { it }, started::add)
        advanceUntilIdle()
        assertEquals(listOf(-1), started)
    }

    @Test fun disposalCancelsLookupAndDoesNotStartPlayback() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        zap.move("playing", 1, { it }, started::add)
        zap.cancel()
        advanceUntilIdle()
        assertTrue(started.isEmpty())
    }

    @Test fun listBoundaryResetsTheUnfulfilledDistance() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        zap.move("playing", -1, { null }, started::add)
        advanceUntilIdle()
        zap.move("playing", 1, { it }, started::add)
        advanceUntilIdle()
        assertEquals(listOf(1), started)
    }

    @Test fun lookupFailureLeavesNextRequestUsable() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        zap.move("playing", 1, { throw IllegalStateException("lookup failed") }, started::add)
        advanceUntilIdle()
        zap.move("playing", 1, { it }, started::add)
        advanceUntilIdle()
        assertEquals(listOf(1), started)
    }

    @Test fun pressesBeforeSelectionIsObservedContinueFromPendingTarget() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this)
        zap.move("playing", 1, { it }, started::add)
        advanceUntilIdle()
        zap.move("playing", 1, { it }, started::add)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), started)
        zap.synchronize("now-playing-target")
        zap.move("now-playing-target", 1, { it }, started::add)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 1), started)
    }

    @Test fun observingOurSelectionDoesNotCancelKeysAlreadyQueuedFromTheOldAnchor() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this, targetId = { "channel-$it" })
        zap.move("channel-0", 1, { it }, started::add)
        advanceUntilIdle()
        zap.move("channel-0", 1, { it }, started::add)
        zap.synchronize("channel-1")
        advanceUntilIdle()
        assertEquals(listOf(1, 2), started)
        zap.synchronize("channel-2")
        zap.move("channel-2", 1, { 2 + it }, started::add)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), started)
    }

    @Test fun reversingBeforeSelectionIsObservedCanReturnToTheOriginalChannel() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this, targetId = { "channel-$it" })
        zap.move("channel-0", 1, { it }, started::add)
        advanceUntilIdle()
        zap.move("channel-0", -1, { it }, started::add)
        zap.synchronize("channel-1")
        advanceUntilIdle()
        assertEquals(listOf(1, 0), started)
    }

    @Test fun boundaryAfterSelectionKeepsTheNextPressRelativeToThatSelection() = runTest {
        val started = mutableListOf<Int>()
        val zap = LiveZapController<Int>(this, targetId = { "channel-$it" })
        zap.move("channel-0", 1, { it }, started::add)
        advanceUntilIdle()
        zap.move("channel-0", 1, { null }, started::add)
        advanceUntilIdle()
        zap.move("channel-1", -1, { 1 + it }, started::add)
        advanceUntilIdle()
        assertEquals(listOf(1, 0), started)
    }
}
