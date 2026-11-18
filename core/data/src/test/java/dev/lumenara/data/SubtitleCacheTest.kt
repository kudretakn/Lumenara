package dev.lumenara.data

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubtitleCacheTest {
    @get:Rule val files = org.junit.rules.TemporaryFolder()

    @Test fun carryOverKeepsDownloadedSubtitleAndTheRollbackCopy() {
        val cache = SubtitleCache(files.root)
        val text = "1\n00:00:01,000 --> 00:00:02,000\nSubtitle\n"
        cache.save("old", text.toByteArray(), "subtitle.srt")
        cache.carryKeys(mapOf("old" to "new"))
        assertEquals(text, cache.find("new")!!.readText())
        assertEquals(text, cache.find("old")!!.readText())
        cache.save("new", text.replace("Subtitle", "Chosen").toByteArray(), "subtitle.srt")
        cache.carryKeys(mapOf("old" to "new"))
        assertEquals(text.replace("Subtitle", "Chosen"), cache.find("new")!!.readText())
    }
    @Test fun cacheFileNameContainsOnlyFilesystemSafeCharacters() {
        val name = subtitleCacheFileName("movie:episode/1", "vtt")

        assertTrue(name.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(name.endsWith(".vtt"))
    }
}
