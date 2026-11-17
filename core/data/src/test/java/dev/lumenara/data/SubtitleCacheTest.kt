package dev.lumenara.data

import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCacheTest {
    @Test fun cacheFileNameContainsOnlyFilesystemSafeCharacters() {
        val name = subtitleCacheFileName("movie:episode/1", "vtt")

        assertTrue(name.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(name.endsWith(".vtt"))
    }
}
