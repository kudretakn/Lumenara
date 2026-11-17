package dev.lumenara.tv

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class PlayerUiStateTest {
    @Test fun snapshotCanBeReadOnWorkerWithoutTouchingPlayerAgain() {
        val owner = Thread.currentThread()
        var position = 1_234L
        var reads = 0
        val player = player { method ->
            check(Thread.currentThread() === owner) { "Player read off its application thread" }
            reads++
            when (method) {
                "getCurrentPosition" -> position
                "getDuration" -> C.TIME_UNSET
                "getCurrentTracks" -> Tracks.EMPTY
                "isCurrentMediaItemLive" -> true
                "isCurrentMediaItemSeekable" -> false
                else -> error("Unexpected Player access: $method")
            }
        }
        val snapshot = readPlayerUiState(player) { check(Thread.currentThread() === owner) }
        position = 9_999
        val readsAfterCapture = reads
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                assertEquals(1_234L, snapshot.position)
                assertEquals(C.TIME_UNSET, snapshot.duration)
                assertSame(Tracks.EMPTY, snapshot.tracks)
                assertTrue(snapshot.isLive)
                assertFalse(snapshot.isSeekable)
            }.get()
            assertEquals(readsAfterCapture, reads)
            assertEquals(9_999L, readPlayerUiState(player) {}.position)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun threadGuardRunsBeforeAnyPlayerGetter() {
        var reads = 0
        val player = player { reads++; error("Player must not be accessed before the guard") }
        val rejection = IllegalStateException("Wrong application thread")
        try {
            readPlayerUiState(player) { throw rejection }
            fail("Expected thread guard rejection")
        } catch (failure: IllegalStateException) {
            assertSame(rejection, failure)
        }
        assertEquals(0, reads)
    }

    private fun player(read: (String) -> Any?): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ -> read(method.name) } as Player
}
