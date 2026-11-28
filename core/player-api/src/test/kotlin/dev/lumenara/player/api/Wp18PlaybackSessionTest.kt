package dev.lumenara.player.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class Wp18PlaybackSessionTest {
    @Test fun silentTsHlsAndTsRoutingDoesNotLoopOrAcceptOldFallback() {
        val session = PlaybackSession()
        val ts = session.item("ts")
        session.select(ts, true, EngineKind.MEDIA3)
        assertTrue(session.fallbackToVlc(ts, 0))
        assertEquals(EngineKind.VLC, session.requestedEngine.value)
        val hls = session.item("hls")
        session.select(hls, true, EngineKind.MEDIA3)
        assertEquals(EngineKind.VLC, session.requestedEngine.value)
        session.switchTo(hls, EngineKind.MEDIA3, 0) // VLC rejects the manifest.
        assertFalse(session.fallbackToVlc(hls, 0))
        assertFalse(session.fallbackToVlc(ts, 0))
        assertEquals(EngineKind.MEDIA3, session.requestedEngine.value)
        val returned = session.item("ts")
        session.select(returned, true, EngineKind.MEDIA3)
        assertFalse(session.fallbackToVlc(ts, 0)) // Same ID, retired handle.
        assertTrue(session.fallbackToVlc(returned, 0))
        assertEquals(EngineKind.VLC, session.requestedEngine.value)
        session.select(session.item("movie"), false, EngineKind.MEDIA3)
        assertEquals(EngineKind.MEDIA3, session.requestedEngine.value)
    }

    @Test fun oneWriterAcrossItemsAndEnginesRejectsOldCallbacks() {
        val session = PlaybackSession()
        val ts = session.item("ts")
        val hls = session.item("hls")
        val first = session.newBinding(ts)
        session.bind(first, "media3-ts", 0, EngineKind.MEDIA3)
        session.transfer(ts, 10_000)
        val vlc = session.newBinding(ts)
        session.bind(vlc, "vlc-ts", session.startPosition(ts, 0), EngineKind.VLC)
        assertNull(session.capture(first, "media3-ts", 20_000, 90_000))
        val next = session.newBinding(hls)
        session.bind(next, "media3-hls", 0, EngineKind.MEDIA3)
        assertNull(session.capture(vlc, "vlc-ts", 30_000, 90_000))
        assertNull(session.capture(next, "vlc-ts", 30_000, 90_000))
        session.unbind(vlc)
        assertEquals(5_000L, session.capture(next, "media3-hls", 5_000, 0)?.position)
        val returned = session.newBinding(ts)
        session.bind(returned, "vlc-return", 0, EngineKind.VLC)
        assertTrue(returned.generation > next.generation)
        assertNull(session.capture(next, "media3-hls", 90_000, 90_000))
        assertEquals(EngineKind.VLC, session.engine)
    }

    @Test fun cancelledPreparationCannotBindAfterNewSelectionOrConsumeItsResume() {
        val session = PlaybackSession()
        val a = session.item("A", 12_000)
        val b = session.item("B", 42_000)
        val obsolete = session.newBinding(a)
        val current = session.newBinding(b)
        assertThrows(IllegalStateException::class.java) { session.bind(obsolete, "old", 0, EngineKind.MEDIA3) }
        assertEquals(12_000L, session.startPosition(a, 0))
        assertNull(session.capture(current, "old", 900_000, 900_000))
        session.bind(current, "B", session.startPosition(b, 0), EngineKind.VLC)
        assertEquals(42_000L, session.startPosition(b, 0))
    }

    @Test fun homeReturnRetainsLatestSeekPauseAndRetryIntentAfterOneShotTransfer() {
        val session = PlaybackSession()
        val movie = session.item("movie")
        session.transfer(movie, 600_000)
        val binding = session.newBinding(movie)
        session.bind(binding, "input", session.startPosition(movie, 0), EngineKind.MEDIA3)
        session.capture(binding, "input", 1_200_000, 3_600_000)
        movie.wantsPlay = false
        movie.retryCount = 2
        session.unbind(binding)
        val returned = session.newBinding(movie)
        session.bind(returned, "return", session.startPosition(movie, 600_000), EngineKind.VLC)
        assertEquals(1_200_000L, session.startPosition(movie, 0))
        assertFalse(movie.wantsPlay)
        assertEquals(2, movie.retryCount)
    }

    @Test fun crossItemHandoffCannotOpenUntilStopAndFdCloseHaveCompleted() = runBlocking {
        val session = PlaybackSession()
        val release = session.holdInput()
        val stopped = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var streams = 1
        val cleanup = launch {
            wp18CloseInput({ events += "cancel" }, { stopped.await(); events += "stop" }, {
                events += "close"; streams--
            })
            session.releaseInput(release)
        }
        val next = async {
            val hls = session.item("new-item")
            session.awaitInputRelease()
            assertEquals(0, streams)
            streams++
            session.bind(session.newBinding(hls), "hls", 0, EngineKind.MEDIA3)
        }
        yield()
        assertEquals(listOf("cancel"), events)
        assertFalse(next.isCompleted)
        assertThrows(IllegalStateException::class.java) { session.holdInput() }
        stopped.complete(Unit)
        cleanup.join()
        next.await()
        assertEquals(listOf("cancel", "stop", "close"), events)
        assertEquals(1, streams)
    }

    @Test fun cancelledWaiterDoesNotReleaseOutgoingInput() = runBlocking {
        val session = PlaybackSession()
        val held = session.holdInput()
        val b = launch { session.awaitInputRelease(); fail("Cancelled B opened") }
        yield()
        b.cancel(); b.join()
        val c = async { session.awaitInputRelease() }
        yield()
        assertFalse(c.isCompleted)
        session.releaseInput(held)
        c.await()
    }

    @Test fun cleanupStillStopsAndClosesWhenReadCancellationThrows() = runBlocking {
        val events = mutableListOf<String>()
        try {
            wp18CloseInput({ events += "cancel"; error("read") }, { events += "stop" }, { events += "close" })
            fail("Expected original error")
        } catch (expected: IllegalStateException) {
            assertEquals("read", expected.message)
        }
        assertEquals(listOf("cancel", "stop", "close"), events)
    }
}
