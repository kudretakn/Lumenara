package dev.lumenara.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercise the matching and grouping used by import/backfill, not the retired refresh path. */
class VersionRefreshTest {
    private fun item(id: String, title: String, year: String = "", tmdb: String = "", source: String = "provider") =
        Item(id, source, title, "Films", "MOVIE", "", "sealed", "$title [tmdb:$tmdb]", 1, "", year = year,
            versionKey = id, displayTitle = title, displayYear = year)

    @Test fun cacheMatchGroupsTranslatedTitlesAndSiblingsWithoutMergingSequelsOrSources() {
        val rows = listOf(item("avatar", "TR: Avatar (2009)").withTmdbMatch(TmdbMatch("19995", "2009")),
            item("dub", "Avatar 4K Dublaj"), item("translation", "Provider English Name", tmdb = "19995"),
            item("sequel", "Avatar: Suyun Yolu", tmdb = "76600"), item("other", "Avatar", source = "other"))
        val assignments = rows.versionAssignments().associateBy { it.id }
        val avatar = listOf("avatar", "dub", "translation").map(assignments::getValue)
        assertEquals(1, avatar.map { it.versionKey }.distinct().size)
        assertEquals("provider|MOVIE|tmdb:19995", avatar.first().versionKey)
        assertEquals(1, avatar.count { it.isRepresentative })
        assertTrue(avatar.all { it.displayYear == "2009" })
        assertEquals("19995", rows.first().tmdbId())
        assertEquals("2009", rows.first().year)
        assertNotEquals(avatar.first().versionKey, assignments.getValue("sequel").versionKey)
        assertNotEquals(avatar.first().versionKey, assignments.getValue("other").versionKey)
        val prepared = rows.map { row ->
            val assigned = assignments.getValue(row.id)
            row.copy(versionKey = assigned.versionKey, isRepresentative = assigned.isRepresentative,
                displayTitle = assigned.displayTitle, displayYear = assigned.displayYear, versionRank = assigned.versionRank)
        }
        assertEquals("Reapplying grouping must not change prepared assignments", rows.versionAssignments(), prepared.versionAssignments())
    }

    @Test fun bothCacheMatchesMergeDespiteDifferentRawNames() {
        val rows = listOf(item("a", "Avatar (2009)"), item("b", "Sağlayıcıdaki farklı ad"))
            .map { it.withTmdbMatch(TmdbMatch("19995", "2009")) }
        val assignments = rows.versionAssignments()
        assertEquals(1, assignments.map { it.versionKey }.distinct().size)
        assertEquals(1, assignments.count { it.isRepresentative })
        assertTrue(rows.all { it.tmdbId() == "19995" })
    }

    @Test fun undatedTitleDoesNotChooseBetweenDatedRemakes() {
        val assignments = listOf(item("unknown", "Film 4K"), item("old", "Film (1999)"),
            item("new", "Film (2024)")).versionAssignments()
        assertEquals(3, assignments.map { it.versionKey }.distinct().size)
        assertEquals("", assignments.single { it.id == "unknown" }.displayYear)
    }

    @Test fun providerIdWinsOverAStaleCacheAndNoMatchLeavesTheMarkerUntouched() {
        val row = item("a", "Film", tmdb = "42")
        assertEquals(row, row.withTmdbMatch(TmdbMatch("43", "2024")))
        assertEquals(row, row.withTmdbMatch(null))
        assertEquals(row, row.withTmdbMatch(TmdbMatch("0")))
        assertEquals("43", row.withTmdbMatch(TmdbMatch("43"), authoritative = true).tmdbId())
    }

    @Test fun groupingScopesSourceAndKindAndTreatsWildcardCharactersLiterally() {
        val rows = listOf(item("a", "100%_ Film", tmdb = "42"), item("dub", "100%_ Film Dublaj"),
            item("other-source", "100%_ Film", tmdb = "42", source = "other"),
            item("series", "100%_ Film", tmdb = "42").copy(kind = "SERIES"), item("literal", "100XY Film"))
        val assignments = rows.versionAssignments().associateBy { it.id }
        assertEquals(assignments.getValue("a").versionKey, assignments.getValue("dub").versionKey)
        assertEquals(4, assignments.values.map { it.versionKey }.distinct().size)
        assertTrue(listOf("other-source", "series", "literal").all {
            assignments.getValue(it).versionKey != assignments.getValue("a").versionKey
        })
    }

    @Test fun onlyPositiveCacheEntriesForTheCurrentIdentityArePersisted() {
        val row = item("a", "Avatar (2009)")
        val json = JSONObject().put("id", 19995).put("release_date", "2009-12-18")
            .put("_identity", tmdbCacheIdentity("movie", "Avatar", "2009", ""))
        assertEquals(TmdbMatch("19995", "2009"), cachedTmdbMatch(row, json))
        assertNull(cachedTmdbMatch(row.copy(title = "Other film"), json))
        assertNull(cachedTmdbMatch(row.copy(kind = "SERIES"), json))
        assertNull(cachedTmdbMatch(row.copy(search = "[tmdb:42]"), json))
        assertNull(cachedTmdbMatch(row, JSONObject(json.toString()).put("_miss", true)))
        assertNull(cachedTmdbMatch(row, JSONObject().put("id", 0)))
        assertEquals("19995", cachedTmdbMatch(row, JSONObject().put("id", 19995))?.id) // legacy cache
    }

    @Test fun aReplacedMatchSplitsAnOldGroupAndReelectsBothRepresentatives() {
        val rows = listOf(item("a", "Film", tmdb = "42").copy(versionKey = "old", isRepresentative = true),
            item("b", "Film", tmdb = "42").copy(versionKey = "old", isRepresentative = false)
                .withTmdbMatch(TmdbMatch("43"), authoritative = true))
        val assignments = rows.versionAssignments()
        assertEquals(2, assignments.map { it.versionKey }.distinct().size)
        assertTrue(assignments.all { it.isRepresentative })
    }
}
