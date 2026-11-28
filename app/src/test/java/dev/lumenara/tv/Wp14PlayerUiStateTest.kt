package dev.lumenara.tv

import androidx.media3.common.Player
import androidx.media3.common.Tracks
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class Wp14PlayerUiStateTest {
    @Test fun periodicPositionSnapshotUsesEventCachedTracks() {
        var trackReads = 0
        var position = 0L
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentTracks" -> { trackReads++; Tracks.EMPTY }
                "getCurrentPosition" -> position
                "getDuration" -> 60_000L
                "isCurrentMediaItemLive", "isCurrentMediaItemSeekable" -> false
                else -> error("Unexpected getter: ${method.name}")
            }
        } as Player
        val initial = readPlayerUiState(player) {}
        assertEquals(1, trackReads)
        repeat(100) {
            position++
            val current = readPlayerUiState(player, tracks = initial.tracks) {}
            assertEquals(position, current.position)
            assertSame(initial.tracks, current.tracks)
        }
        assertEquals(1, trackReads)
        readPlayerUiState(player) {} // A native/player event refreshes the track snapshot.
        assertEquals(2, trackReads)
    }
}
