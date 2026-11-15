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
    @Test fun versionCandidateProjectionContainsOnlyGroupingData() {
        val projected = VersionCandidate("id", "provider", "Kıyı Dublaj", "MOVIE",
            "Kıyı Dublaj [tmdb:42]", "2024", "Films").asItem()
        assertEquals("provider|MOVIE|tmdb:42", projected.productionKey())
        assertEquals("2024", projected.year)
        assertEquals("", projected.encryptedUrl)
        assertEquals("", projected.summary)
        assertEquals("", projected.castNames)
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

    @Test fun importKeysMatchExistingGroupingIncludingMissingAndConflictingTmdbIds() {
        val rows = listOf(
            item("dub", "Kıyı (Türkçe Dublaj)", tmdb = "00042"),
            item("sub", "Kıyı (Altyazılı)"),
            item("translated", "The Coast", tmdb = "42"),
            item("conflict1", "Başka Film", tmdb = "100"),
            item("conflict2", "Başka Film", tmdb = "101"),
            item("ambiguous", "Başka Film"),
            item("remake", "Kıyı", year = "1999"),
            item("otherSource", "Kıyı", source = "other"),
            item("series", "Kıyı", kind = "SERIES"),
            item("live", "Kıyı", kind = "LIVE"),
            item("episode", "Kıyı S01E01", kind = "EPISODE"),
        )
        val assigned = rows.versionAssignments()
        assertEquals(rows.versionGroups().map { it.map(Item::id).toSet() }.toSet(),
            assigned.groupBy { it.versionKey }.values.map { it.map(VersionAssignment::id).toSet() }.toSet())
        val keys = assigned.associate { it.id to it.versionKey }
        assertEquals("provider|MOVIE|tmdb:42", keys["dub"])
        assertEquals(keys["dub"], keys["sub"])
        assertEquals(keys["dub"], keys["translated"])
        assertEquals("ambiguous", keys["ambiguous"])
        assertEquals("live", keys["live"])
        assertEquals("episode", keys["episode"])
        assertNotEquals(keys["dub"], keys["remake"])
        assertNotEquals(keys["dub"], keys["otherSource"])
        assertNotEquals(keys["dub"], keys["series"])
    }

    @Test fun representativeRankAndDisplayFieldsPreserveRawProviderMetadata() {
        val rows = listOf(item("sub", "Kıyı (2024) Altyazılı"),
            item("z-dub", "Kıyı (Türkçe Dublaj)"), item("original", "Kıyı"), item("a-dub", "Kıyı TR Dublaj"))
        val assigned = rows.versionAssignments()
        assertEquals(listOf("a-dub", "z-dub", "original", "sub"), assigned.sortedBy { it.versionRank }.map { it.id })
        assertEquals("a-dub", assigned.single { it.isRepresentative }.id)
        assertTrue(assigned.all { it.displayTitle == "Kıyı" && it.displayYear == "2024" })
        assertEquals("Kıyı (2024) Altyazılı", rows.first().title)
        assertEquals("Kıyı (2024) Altyazılı", rows.first().displayVersion().title) // legacy/default row
    }

    @Test fun groupingIsIndependentOfProviderOrderAndStagingBatchBoundaries() {
        val rows = listOf(item("sub", "Kıyı Altyazılı")) +
            (0..130).map { item("filler$it", "Film $it") } + item("dub", "Kıyı Dublaj", tmdb = "42")
        val expected = rows.versionAssignments().associateBy { it.id }
        assertEquals(expected, rows.reversed().versionAssignments().associateBy { it.id })
        assertEquals(expected["sub"]!!.versionKey, expected["dub"]!!.versionKey)
        assertFalse(expected["sub"]!!.isRepresentative)
    }
}
