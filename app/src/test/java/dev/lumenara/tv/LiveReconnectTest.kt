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
                assertFalse(liveShouldReconnect(kind, signal, playing = true,
                    positionAdvanced = true, unchangedForMs = 8_000))
            }
        }
    }

    @Test fun ownStopNeverReconnects() {
        for (signal in LivePlaybackSignal.values()) {
            assertFalse(liveShouldReconnect("LIVE", signal, ownStop = true,
                playing = true, positionAdvanced = true, unchangedForMs = 8_000))
        }
    }

    @Test fun frozenTimeReconnectsAtEightSeconds() {
        val progress = LiveProgress().sample(0, 0, true).sample(1_500, 100, true)
        assertFalse(liveShouldReconnect("LIVE", playing = true,
            positionAdvanced = progress.positionAdvanced,
            unchangedForMs = progress.sample(1_500, 8_099, true).unchangedForMs(8_099)))
        assertTrue(liveShouldReconnect("LIVE", playing = true,
            positionAdvanced = progress.positionAdvanced,
            unchangedForMs = progress.sample(1_500, 8_100, true).unchangedForMs(8_100)))
    }

    @Test fun unknownOrZeroTimeFromStartNeverArmsStallCheck() {
        for (position in listOf(-1L, 0L, 1_500L)) {
            val progress = LiveProgress().sample(position, 0, true)
                .sample(position, 16_000, true)
            assertFalse(progress.positionAdvanced)
            assertFalse(liveShouldReconnect("LIVE", playing = true,
                positionAdvanced = progress.positionAdvanced,
                unchangedForMs = progress.unchangedForMs(16_000)))
        }
    }

    @Test fun unknownTimeChangingToZeroDoesNotArmStallCheck() {
        val progress = LiveProgress().sample(-1, 0, true).sample(0, 500, true)
        assertFalse(progress.positionAdvanced)
        assertFalse(liveShouldReconnect("LIVE", playing = true,
            positionAdvanced = progress.positionAdvanced,
            unchangedForMs = progress.unchangedForMs(10_500)))
    }

    @Test fun initialBufferingForTenSecondsDoesNotReconnect() {
        // Media3 treats playWhenReady + STATE_BUFFERING as active for sampling.
        val progress = LiveProgress().sample(0, 0, true).sample(0, 10_000, true)
        assertFalse(liveShouldReconnect("LIVE", playing = true,
            positionAdvanced = progress.positionAdvanced,
            unchangedForMs = progress.unchangedForMs(10_000)))
    }

    @Test fun restartRequiresNewPositionAdvance() {
        val previous = LiveProgress().sample(0, 0, true).sample(1_500, 100, true)
        assertTrue(previous.positionAdvanced)
        val restarted = LiveProgress().sample(0, 200, true).sample(0, 10_200, true)
        assertFalse(liveShouldReconnect("LIVE", playing = true,
            positionAdvanced = restarted.positionAdvanced,
            unchangedForMs = restarted.unchangedForMs(10_200)))
    }

    @Test fun advancingTimeResetsStallDeadline() {
        val progress = LiveProgress().sample(1_500, 100, true).sample(2_000, 8_100, true)
        assertFalse(liveShouldReconnect("LIVE", playing = true,
            positionAdvanced = progress.positionAdvanced,
            unchangedForMs = progress.unchangedForMs(8_100)))
    }

    @Test fun pausePreviewAndMultiViewNeverReconnect() {
        for (signal in LivePlaybackSignal.values()) {
            assertFalse(liveShouldReconnect("LIVE", signal, paused = true,
                playing = true, positionAdvanced = true, unchangedForMs = 8_000))
            assertFalse(liveShouldReconnect("LIVE", signal, preview = true,
                playing = true, positionAdvanced = true, unchangedForMs = 8_000))
            assertFalse(liveShouldReconnect("LIVE", signal, multi = true,
                playing = true, positionAdvanced = true, unchangedForMs = 8_000))
        }
        assertFalse(liveShouldReconnect("LIVE", playing = false,
            positionAdvanced = true, unchangedForMs = 8_000))
    }

    @Test fun pauseClearsElapsedStallTime() {
        val progress = LiveProgress().sample(0, 0, true).sample(1_500, 100, true)
            .sample(1_500, 7_100, false).sample(1_500, 20_100, true)
        assertFalse(liveShouldReconnect("LIVE", playing = true,
            positionAdvanced = progress.positionAdvanced,
            unchangedForMs = progress.unchangedForMs(20_100)))
    }
}
