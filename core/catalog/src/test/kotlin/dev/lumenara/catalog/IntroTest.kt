package dev.lumenara.catalog

import org.junit.Assert.*
import org.junit.Test

class IntroTest {
    @Test fun requiresExplicitIntervalWithinTheEpisode() {
        assertFalse(canSkipIntro(-1, -1, 0, 60000))
        assertFalse(canSkipIntro(0, 90000, 4000, 60000))
        assertFalse(canSkipIntro(2000, 9000, 9000, 60000))
        assertTrue(canSkipIntro(2000, 9000, 2000, 60000))
    }
}
