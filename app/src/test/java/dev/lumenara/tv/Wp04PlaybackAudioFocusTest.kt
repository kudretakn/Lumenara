package dev.lumenara.tv

import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test

class Wp04PlaybackAudioFocusTest {
    private class Fixture(var result: Int = AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        var requests = 0
        var abandons = 0
        var playing = false
        var intent = false
        val owner = PlaybackAudioFocus({ requests++; result }, { abandons++ }, { playing = it }, { intent = it })
    }

    @Test fun transientAssistantFocusPausesAndGainResumesWithoutAnotherRequest() {
        for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            val f = Fixture()
            f.owner.play()
            assertTrue(f.playing)
            f.owner.onFocusChange(loss)
            assertFalse(f.playing)
            assertTrue(f.intent)
            f.owner.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertTrue(f.playing)
            assertEquals(1, f.requests)
        }
    }

    @Test fun userPauseDuringTransientLossPreventsAutomaticResume() {
        val f = Fixture()
        f.owner.play()
        f.owner.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        f.owner.pause()
        f.owner.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(f.playing)
        assertFalse(f.intent)
        assertEquals(1, f.abandons)
    }

    @Test fun permanentLossRequiresExplicitPlay() {
        val f = Fixture()
        f.owner.play()
        f.owner.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        f.owner.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(f.playing)
        assertFalse(f.intent)
        f.owner.play()
        assertTrue(f.playing)
        assertEquals(2, f.requests)
    }

    @Test fun delayedFocusStartsOnlyOnGain() {
        val f = Fixture(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        f.owner.play()
        assertFalse(f.playing)
        f.owner.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(f.playing)
    }

    @Test fun deniedFocusNeverStartsAndExplicitPlayCanRetry() {
        val f = Fixture(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        f.owner.play()
        assertFalse(f.playing)
        f.owner.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(f.playing)
        f.result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        f.owner.play()
        assertTrue(f.playing)
    }

    @Test fun closePausesAbandonsOnceAndIgnoresQueuedCallbacks() {
        val f = Fixture()
        f.owner.play()
        f.owner.close()
        f.owner.close()
        f.owner.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        f.owner.play()
        assertFalse(f.playing)
        assertFalse(f.owner.canPlay)
        assertEquals(1, f.requests)
        assertEquals(1, f.abandons)
        assertTrue("Lifecycle cleanup preserves the user's resume intent", f.intent)
    }

    @Test fun nativeStopReleasesFocusAndRetryRequestsItAgain() {
        val f = Fixture()
        f.owner.play()
        f.playing = false // Native end/error precedes this notification.
        f.owner.stopped()
        f.owner.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(f.playing)
        assertTrue(f.intent)
        assertEquals(1, f.abandons)
        f.owner.play()
        assertTrue(f.playing)
        assertEquals(2, f.requests)
    }
}
