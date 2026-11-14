package dev.lumenara.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltInOpenKeyTest {
    @Test
    fun apiKeyIsEmptyWhenEnvironmentVariableIsAbsent() {
        if (System.getenv("OPENSUBTITLES_API_KEY") == null) {
            assertEquals("", BuildConfig.OPENSUBTITLES_API_KEY)
        }
    }

    @Test
    fun tmdbTokenIsEmptyWhenEnvironmentVariableIsAbsent() {
        if (System.getenv("TMDB_READ_TOKEN") == null) {
            assertEquals("", BuildConfig.TMDB_READ_TOKEN)
        }
    }
}
