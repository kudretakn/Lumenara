package dev.lumenara.tv

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class Wp04PlaybackOwnershipTest {
    @Test fun cancelledBPreparationNeverWritesAPosition() {
        val a = PlaybackOwnership("A")
        val aBinding = a.newBinding()
        a.bind(aBinding, "input-A", 0)
        val writes = mutableMapOf<String, Long>()
        fun save(owner: PlaybackOwnership, binding: PlaybackOwnership.Binding, media: String, position: Long) {
            owner.capture(binding, media, position, 900_000)?.let { writes[owner.itemId] = it.position }
        }
        save(a, aBinding, "input-A", 120_000)
        a.unbind(aBinding)
        val b = PlaybackOwnership("B")
        val preparingB = b.newBinding()
        // B's URL/preferences never completed. The reused engine still reports A.
        save(b, preparingB, "input-A", 120_000)
        val c = PlaybackOwnership("C")
        val cBinding = c.newBinding()
        c.bind(cBinding, "input-C", 0)
        save(c, cBinding, "input-C", 4_000)
        assertEquals(mapOf("A" to 120_000L, "C" to 4_000L), writes)
    }

    @Test fun replacedGenerationAndWrongMediaCannotWrite() {
        val owner = PlaybackOwnership("episode")
        val old = owner.newBinding()
        owner.bind(old, "old", 0)
        val current = owner.newBinding()
        owner.bind(current, "current", 20_000)
        assertNull(owner.capture(old, "old", 99_000, 100_000))
        assertNull(owner.capture(current, "old", 99_000, 100_000))
        owner.unbind(old)
        assertNotNull(owner.capture(current, "current", 21_000, 100_000))
    }

    @Test fun transferredPositionIsConsumedOnceAndHomeKeepsLatestSnapshot() {
        val owner = PlaybackOwnership("movie")
        owner.transfer(600_000)
        assertEquals(600_000L, owner.startPosition(12_000))
        val first = owner.newBinding()
        owner.bind(first, "media3", owner.startPosition(12_000))
        owner.capture(first, "media3", 1_200_000, 3_600_000)
        owner.unbind(first) // Home destroys the engine, not the session owner.
        assertEquals(1_200_000L, owner.startPosition(12_000))
        val returned = owner.newBinding()
        owner.bind(returned, "media3-return", owner.startPosition(12_000))
        assertEquals(1_200_000L, owner.startPosition(600_000))
    }

    @Test fun cancelledPreparationDoesNotConsumeInitialPosition() {
        val owner = PlaybackOwnership("movie", 42_000)
        val cancelled = owner.newBinding()
        owner.unbind(cancelled)
        assertEquals(42_000L, owner.startPosition(0))
    }

    @Test fun outgoingCleanupCannotOverwriteTransfer() {
        val owner = PlaybackOwnership("movie")
        val outgoing = owner.newBinding()
        owner.bind(outgoing, "vlc", 0)
        owner.transfer(120_000)
        assertNull(owner.capture(outgoing, "vlc", 0, 900_000))
        owner.unbind(outgoing)
        assertEquals(120_000L, owner.startPosition(0))
    }

    @Test fun switchingEngineBeforeBindingPreservesSavedResumeFallback() {
        val owner = PlaybackOwnership("movie")
        owner.transfer(-1)
        assertEquals(37_000L, owner.startPosition(37_000))
        val withInitial = PlaybackOwnership("movie", 42_000)
        withInitial.transfer(-1)
        assertEquals(42_000L, withInitial.startPosition(37_000))
    }

    @Test fun freshSessionUsesSavedPositionAndPauseIntentSurvivesHome() {
        val owner = PlaybackOwnership("movie")
        assertEquals(23_000L, owner.startPosition(23_000))
        owner.wantsPlay = false
        val binding = owner.newBinding()
        owner.bind(binding, "input", 23_000)
        owner.unbind(binding)
        assertFalse(owner.wantsPlay)
        assertEquals(23_000L, owner.startPosition(0))
    }

    @Test fun handoffWaitsForNativeStopAndDescriptorClose() = runBlocking {
        val owner = PlaybackOwnership("movie")
        val release = owner.holdInput()
        owner.transfer(10_000)
        var media3Opened = false
        val next = async { owner.awaitInputRelease(); media3Opened = true }
        yield()
        assertFalse(media3Opened)
        owner.releaseInput(release)
        next.await()
        assertTrue(media3Opened)
    }
}
