package dev.lumenara.data

import org.junit.Assert.*
import org.junit.Test

class RefreshGateTest {
    @Test fun foregroundAndPlaybackDeferForAtMostThirtyMinutes() {
        val deadline = 1_800_000L
        try {
            RefreshGate.foreground = false
            RefreshGate.playing = false
            assertFalse(RefreshGate.busy)
            RefreshGate.foreground = true
            assertTrue(RefreshGate.defer(0, deadline))
            RefreshGate.foreground = false
            RefreshGate.playing = true
            assertTrue(RefreshGate.defer(deadline - 1, deadline))
            assertFalse(RefreshGate.defer(deadline, deadline))
            assertTrue(RefreshGate.busy) // Worker skips this occurrence; it never overrides playback.
        } finally {
            RefreshGate.foreground = false
            RefreshGate.playing = false
        }
    }
}
