package dev.lumenara.vlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VlcRuntimeTest {
    @Test
    fun playbackSharesLazilyCreatedRuntime() {
        var creations = 0
        val runtime = VlcRuntime {
            creations++
            Any() // Stand-in for the native LibVLC instance.
        }
        assertEquals("LibVLC must be created only on first playback", 0, creations)

        val firstPlaybackLibrary = runtime.get()
        val secondPlaybackLibrary = runtime.get()

        assertSame("Playbacks must share LibVLC", firstPlaybackLibrary, secondPlaybackLibrary)
        assertEquals("Two playbacks must create LibVLC exactly once", 1, creations)
    }
}
