package dev.lumenara.tv

import dev.lumenara.player.api.EngineKind
import dev.lumenara.player.api.PlaybackSession
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class Wp18PlaybackOwnershipTest {
    @Test fun itemFacadesShareOneGenerationAndProgressWriter() {
        val session = PlaybackSession()
        val writes = mutableListOf<Pair<String, Long>>()
        val a = PlaybackOwnership("A", session = session) { writes += "A" to it.position }
        val b = PlaybackOwnership("B", session = session) { writes += "B" to it.position }
        a.select(true, EngineKind.MEDIA3)
        val old = a.newBinding()
        a.bind(old, "A-input", 0)
        a.save(old, "A-input", 10_000, 60_000)
        b.select(true, EngineKind.MEDIA3)
        val current = b.newBinding()
        b.bind(current, "B-input", 0, EngineKind.VLC)
        a.save(old, "A-input", 60_000, 60_000)
        a.saveCurrent(60_000, 60_000)
        b.save(current, "A-input", 60_000, 60_000)
        b.saveCurrent(5_000, 60_000)
        assertEquals(listOf("A" to 10_000L, "B" to 5_000L), writes)
        assertTrue(current.generation > old.generation)
    }

    @Test fun differentItemFacadeWaitsForNativeCleanup() = runBlocking {
        val session = PlaybackSession()
        val a = PlaybackOwnership("A", session = session)
        val b = PlaybackOwnership("B", session = session)
        val held = a.holdInput()
        val openingB = async { b.awaitInputRelease() }
        yield()
        assertFalse(openingB.isCompleted)
        a.releaseInput(held)
        openingB.await()
        val nextHeld = b.holdInput()
        a.releaseInput(held) // A's repeated cleanup cannot release B's lease.
        assertThrows(IllegalStateException::class.java) { a.holdInput() }
        b.releaseInput(nextHeld)
    }
}
