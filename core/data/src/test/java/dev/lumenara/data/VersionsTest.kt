package dev.lumenara.data

import org.junit.Assert.*
import org.junit.Test

class VersionsTest {
    private fun item(id: String, title: String, year: String = "2024", source: String = "provider", tmdb: String = "", kind: String = "MOVIE") =
        Item(id, source, title, "Films", kind, id, "sealed", "$title [tmdb:$tmdb]", 0, "", year = year)

    @Test fun oneCardPrefersDubbedAndRetainsTheProviderIdentity() {
        val rows = listOf(item("sub", "Kıyı (Altyazılı)"), item("dub", "Kıyı (Türkçe Dublaj)"))
        val card = rows.mergeVersions().single()
        assertEquals("dub", card.id); assertEquals("Kıyı", card.title)
        assertEquals(2, rows.size)
        assertTrue(rows.first().sameProduction(rows.last()))
    }
    @Test fun remakeSourceAndMediaKindAreIndependent() {
        val rows = listOf(item("1", "Kıyı"), item("2", "Kıyı", "1999"), item("3", "Kıyı", source = "other"), item("4", "Kıyı", kind = "SERIES"))
        assertEquals(4, rows.mergeVersions().size)
    }
    @Test fun tmdbCanMergeTranslatedTitlesButConflictingIdsNeverMerge() {
        assertEquals(1, listOf(item("1", "Kıyı", tmdb = "42"), item("2", "The Coast", tmdb = "42")).mergeVersions().size)
        assertEquals(2, listOf(item("1", "Kıyı", tmdb = "42"), item("2", "Kıyı", tmdb = "43")).mergeVersions().size)
        assertEquals(1, listOf(item("1", "Kıyı", tmdb = "42"), item("2", "Kıyı (Dublaj)")).mergeVersions().size)
    }
    @Test fun seriesMergeAtParentLevelAndLiveChannelsRemainIndependent() {
        assertEquals(1, listOf(item("1", "Kıyı Dublaj", kind = "SERIES"), item("2", "Kıyı Altyazılı", kind = "SERIES")).mergeVersions().size)
        assertEquals(2, listOf(item("1", "Kıyı Dublaj", kind = "LIVE"), item("2", "Kıyı Altyazılı", kind = "LIVE")).mergeVersions().size)
    }
}
