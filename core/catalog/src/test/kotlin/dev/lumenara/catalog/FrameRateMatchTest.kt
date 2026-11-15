package dev.lumenara.catalog

import org.junit.Assert.*
import org.junit.Test

class FrameRateMatchTest {
    private fun mode(id: Int, hz: Float, width: Int = 1920, height: Int = 1080) = FrameRateMode(id, width, height, hz)
    private val current = mode(1, 60f)

    @Test fun fractionalCinemaAcceptsTvRates() {
        for (hz in listOf(23.976f, 24f, 47.95f, 48f)) {
            val candidate = mode(2, hz)
            assertEquals(candidate, matchFrameRate(24000f / 1001f, current, listOf(current, candidate)))
        }
    }

    @Test fun broadcastRatesUseExactMultiples() {
        val fifty = mode(2, 50f)
        assertEquals(fifty, matchFrameRate(25f, current, listOf(current, fifty)))
        for (fps in listOf(29.97f, 30f)) for (hz in listOf(59.94f, 60f)) {
            val candidate = mode(2, hz)
            assertEquals(candidate, matchFrameRate(fps, current, listOf(candidate, fifty)))
        }
    }

    @Test fun resolutionAndUnsupportedRatesNeverChange() {
        assertNull(matchFrameRate(24f, current, listOf(current, mode(2, 24f, 3840, 2160))))
        assertNull(matchFrameRate(25f, current, listOf(current)))
        assertNull(matchFrameRate(24f, current, emptyList()))
    }

    @Test fun prefersLowestMultipleAndKeepsEqualCurrentMode() {
        val cinema = mode(2, 24f)
        assertEquals(cinema, matchFrameRate(24f, current, listOf(mode(3, 120f), mode(4, 48f), cinema)))
        assertEquals(current, matchFrameRate(30f, current, listOf(mode(2, 60f), current)))
    }

    @Test fun invalidRatesAreIgnored() {
        for (fps in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(matchFrameRate(fps, current, listOf(current)))
        assertNull(matchFrameRate(24f, current, listOf(mode(2, Float.NaN), mode(3, -24f))))
    }

    @Test fun preferenceDefaultsToMoviesAndEpisodes() {
        for (kind in listOf("MOVIE", "EPISODE", "LIVE", "SERIES")) {
            assertFalse(shouldMatchFrameRate("Kapalı", kind))
            assertTrue(shouldMatchFrameRate("Her zaman", kind))
            assertEquals(kind == "MOVIE" || kind == "EPISODE", shouldMatchFrameRate(FRAME_RATE_DEFAULT, kind))
        }
        assertFalse(shouldMatchFrameRate("unknown", "MOVIE"))
    }
}
