package dev.lumenara.data

import org.junit.Assert.*
import org.junit.Test

class VersionsTest {
    @Test fun sameTmdbIdOverridesProviderTitlesAndMissingOrConflictingYears() {
        val assigned = listOf(item("first", "TR: Avatar (2009)", year = "2009", tmdb = "00019995"),
            item("second", "Completely different provider title [4K", year = "", tmdb = "19995"),
            item("third", "Avatar HD", year = "2010", tmdb = "19995"),
            item("sequel", "Avatar: Suyun Yolu", year = "2022", tmdb = "76600"),
            item("source", "Avatar", tmdb = "19995", source = "other"),
            item("series", "Avatar", tmdb = "19995", kind = "SERIES")).versionAssignments()
        val avatar = assigned.filter { it.id in listOf("first", "second", "third") }
        assertEquals(listOf("provider|MOVIE|tmdb:19995"), avatar.map { it.versionKey }.distinct())
        assertEquals(1, avatar.count { it.isRepresentative })
        assertEquals(4, assigned.map { it.versionKey }.distinct().size)
    }

    @Test fun undatedRepresentativeInheritsTheUniqueYearInBothDisplayPaths() {
        val rows = listOf(item("a", "Avatar 4K", year = ""), item("b", "Avatar (2009)", year = ""))
        assertEquals(1, rows.versionAssignments().map { it.versionKey }.distinct().size)
        assertEquals("2009", rows.mergeVersions().single().year)
        assertTrue(rows.versionAssignments().all { it.displayYear == "2009" })
    }

    @Test fun numericSequelTitlesKeepTheirNumberAndUseTheReleaseAnnotationAsYear() {
        val assigned = listOf(item("dated", "Bıçak Sırtı 2049 (2017)", year = ""),
            item("undated", "Bıçak Sırtı 2049 [4K", year = ""),
            item("original", "Bıçak Sırtı (1982)", year = "")).versionAssignments()
        val sequel = assigned.filter { it.id != "original" }
        assertEquals(1, sequel.map { it.versionKey }.distinct().size)
        assertTrue(sequel.all { it.displayTitle == "Bıçak Sırtı 2049" && it.displayYear == "2017" })
        assertNotEquals(sequel.first().versionKey, assigned.single { it.id == "original" }.versionKey)
    }

    @Test fun avatarRenditionsWithAnOptionalYearShareOneCardAndKeepTheSequelSeparate() {
        val rows = listOf(item("tr", "TR: Avatar (2009)", year = ""),
            item("4k", "Avatar 4K", year = ""), item("sub", "Avatar [Altyazılı]", year = ""),
            item("hd", "[TR] Avatar HD Türkçe Dublaj", year = ""),
            item("sequel", "Avatar: Suyun Yolu", year = ""))
        val assigned = rows.versionAssignments()
        val avatar = assigned.filter { it.id != "sequel" }
        assertEquals(1, avatar.map { it.versionKey }.distinct().size)
        assertEquals(1, avatar.count { it.isRepresentative })
        assertEquals(listOf(0, 1, 2, 3), avatar.map { it.versionRank }.sorted())
        assertTrue(avatar.all { it.displayTitle == "Avatar" && it.displayYear == "2009" })
        assertNotEquals(avatar.first().versionKey, assigned.single { it.id == "sequel" }.versionKey)
        assertEquals(assigned.associateBy { it.id }, rows.reversed().versionAssignments().associateBy { it.id })
    }

    @Test fun anUndatedVersionDoesNotGuessBetweenDatedRemakesOrConflictingTmdbIds() {
        val remakes = listOf(item("old", "TR: Film (1999)", year = ""),
            item("new", "Film (2024) HD", year = ""), item("unknown", "Film 4K", year = ""))
        assertEquals(3, remakes.versionGroups().size)
        val conflict = listOf(item("1", "TR: Avatar (2009)", year = "", tmdb = "42"),
            item("2", "Avatar 4K", year = "", tmdb = "43"), item("3", "Avatar HD", year = ""))
        assertEquals(3, conflict.versionGroups().size)
    }

    @Test fun exactStreamPreferenceKeepsSameAudioQualityChoicesAndOldAudioPreferencesWork() {
        val rows = listOf(item("hd", "Avatar HD Dublaj"), item("4k", "Avatar 4K Dublaj"),
            item("sub", "Avatar [Altyazılı]"))
        assertEquals("4k", rows.preferredVersion("id:4k", rows.first()).id)
        assertEquals("sub", rows.preferredVersion("SUBTITLED", rows.first()).id)
        assertEquals("hd", rows.preferredVersion("id:removed", rows.first()).id)
    }

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
