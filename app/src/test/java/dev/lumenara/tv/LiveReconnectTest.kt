package dev.lumenara.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReconnectTest {
    @Test fun liveEndReachedReconnects() {
        assertTrue(liveShouldReconnect("LIVE", LivePlaybackSignal.END_REACHED))
    }

    @Test fun liveUnexpectedStopReconnects() {
        assertTrue(liveShouldReconnect("LIVE", LivePlaybackSignal.STOPPED))
    }

    @Test fun vodAndEpisodeNeverReconnect() {
        for (kind in listOf("VOD", "MOVIE", "EPISODE")) {
            for (signal in LivePlaybackSignal.values()) {
                assertFalse(liveShouldReconnect(kind, signal, playing = true, unchangedForMs = 8_000))
            }
        }
    }

    @Test fun ownStopNeverReconnects() {
        for (signal in LivePlaybackSignal.values()) {
            assertFalse(liveShouldReconnect("LIVE", signal, ownStop = true,
                playing = true, unchangedForMs = 8_000))
        }
    }

    @Test fun frozenTimeReconnectsAtEightSeconds() {
        val progress = LiveProgress().sample(1_500, 100, active = true)
        assertFalse(liveShouldReconnect("LIVE", playing = true,
            unchangedForMs = progress.sample(1_500, 8_099, true).unchangedForMs(8_099)))
        assertTrue(liveShouldReconnect("LIVE", playing = true,
            unchangedForMs = progress.sample(1_500, 8_100, true).unchangedForMs(8_100)))
    }

    @Test fun advancingTimeResetsStallDeadline() {
        val progress = LiveProgress().sample(1_500, 100, true).sample(2_000, 8_100, true)
        assertFalse(liveShouldReconnect("LIVE", playing = true,
            unchangedForMs = progress.unchangedForMs(8_100)))
    }

    @Test fun pausePreviewAndMultiViewNeverReconnect() {
        for (signal in LivePlaybackSignal.values()) {
            assertFalse(liveShouldReconnect("LIVE", signal, paused = true,
                playing = true, unchangedForMs = 8_000))
            assertFalse(liveShouldReconnect("LIVE", signal, preview = true,
                playing = true, unchangedForMs = 8_000))
            assertFalse(liveShouldReconnect("LIVE", signal, multi = true,
                playing = true, unchangedForMs = 8_000))
        }
        assertFalse(liveShouldReconnect("LIVE", playing = false, unchangedForMs = 8_000))
    }

    @Test fun pauseClearsElapsedStallTime() {
        val progress = LiveProgress().sample(1_500, 100, true)
            .sample(1_500, 7_100, false).sample(1_500, 20_100, true)
        assertFalse(liveShouldReconnect("LIVE", playing = true,
            unchangedForMs = progress.unchangedForMs(20_100)))
    }
}
