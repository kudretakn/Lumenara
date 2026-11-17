package dev.lumenara.data

import androidx.sqlite.db.SupportSQLiteProgram
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VersionRefreshTest {
    private fun item(id: String, title: String, year: String = "", tmdb: String = "", source: String = "provider") =
        Item(id, source, title, "Films", "MOVIE", "", "sealed", "$title [tmdb:$tmdb]", 1, "", year = year,
            versionKey = id, displayTitle = title, displayYear = year)

    private class Store(items: List<Item>, val cache: MutableMap<String, TmdbMatch> = mutableMapOf()) {
        val rows = items.associateBy { it.id }.toMutableMap()
        var writes = 0
        val loaded = mutableSetOf<String>()
        val refresher = VersionRefresher(
            seeds = { ids -> ids.mapNotNull(rows::get) },
            // SQL returns a superset. The refresher must reject sequels and unrelated titles.
            related = { batch -> rows.values.filter { it.source == batch.first().source && it.kind == batch.first().kind } },
            cached = { loaded += it.id; cache[it.id] },
            persist = { matches, assignments ->
                writes++
                matches.forEach { update -> rows[update.id] = rows.getValue(update.id).copy(search = update.search, year = update.year) }
                assignments.forEach { update -> rows[update.id] = rows.getValue(update.id).copy(versionKey = update.versionKey,
                    isRepresentative = update.isRepresentative, displayTitle = update.displayTitle,
                    displayYear = update.displayYear, versionRank = update.versionRank) }
            },
        )
    }

    @Test fun lateCacheMatchRekeysTranslatedTitlesAndAllOldSiblingsWithoutLoadingTheCatalog() = runTest {
        val store = Store(listOf(item("avatar", "TR: Avatar (2009)"), item("dub", "Avatar 4K Dublaj"),
            item("translation", "Provider English Name", tmdb = "19995"),
            item("sequel", "Avatar: Suyun Yolu", tmdb = "76600"), item("other", "Avatar", source = "other")),
            mutableMapOf("avatar" to TmdbMatch("19995", "2009")))
        store.refresher.refresh(listOf("avatar"))
        val avatar = listOf("avatar", "dub", "translation").map { store.rows.getValue(it) }
        assertEquals(1, avatar.map { it.versionKey }.distinct().size)
        assertEquals("provider|MOVIE|tmdb:19995", avatar.first().versionKey)
        assertEquals(1, avatar.count { it.isRepresentative })
        assertTrue(avatar.all { it.displayYear == "2009" })
        assertEquals("19995", store.rows.getValue("avatar").tmdbId())
        assertEquals("2009", store.rows.getValue("avatar").year)
        assertEquals(setOf("avatar", "dub", "translation"), store.loaded)
        assertEquals("sequel", store.rows.getValue("sequel").versionKey)
        store.refresher.refresh(listOf("avatar"))
        assertEquals(1, store.writes) // Room invalidation cannot start an endless write loop.
    }

    @Test fun bothVisibleCacheOnlyMatchesMergeDespiteDifferentRawNames() = runTest {
        val store = Store(listOf(item("a", "Avatar (2009)"), item("b", "Sağlayıcıdaki farklı ad")),
            mutableMapOf("a" to TmdbMatch("19995", "2009"), "b" to TmdbMatch("19995", "2009")))
        store.refresher.refresh(listOf("a", "b"))
        assertEquals(1, store.rows.values.map { it.versionKey }.distinct().size)
        assertEquals(1, store.rows.values.count { it.isRepresentative })
        assertTrue(store.rows.values.all { it.tmdbId() == "19995" })
    }

    @Test fun undatedSeedChecksEveryYearBeforeChoosingAUniqueFilm() = runTest {
        val store = Store(listOf(item("unknown", "Film 4K"), item("old", "Film (1999)"), item("new", "Film (2024)")))
        store.refresher.refresh(listOf("unknown"))
        assertEquals(3, store.rows.values.map { it.versionKey }.distinct().size)
        assertEquals("", store.rows.getValue("unknown").displayYear)
    }

    @Test fun providerIdWinsOverAStaleCacheAndNoMatchLeavesTheMarkerUntouched() {
        val row = item("a", "Film", tmdb = "42")
        assertEquals(row, row.withTmdbMatch(TmdbMatch("43", "2024")))
        assertEquals(row, row.withTmdbMatch(null))
        assertEquals(row, row.withTmdbMatch(TmdbMatch("0")))
        assertEquals("43", row.withTmdbMatch(TmdbMatch("43"), authoritative = true).tmdbId())
    }

    @Test fun siblingProbeScopesTheSourceAndKindAndEscapesLiteralLikeCharacters() {
        val query = relatedVersionsQuery(listOf(item("a", "100%_ Film", tmdb = "42")))
        val bindings = mutableMapOf<Int, Any?>()
        query.bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { bindings[index] = null }
            override fun bindLong(index: Int, value: Long) { bindings[index] = value }
            override fun bindDouble(index: Int, value: Double) { bindings[index] = value }
            override fun bindString(index: Int, value: String) { bindings[index] = value }
            override fun bindBlob(index: Int, value: ByteArray) { bindings[index] = value }
            override fun clearBindings() { bindings.clear() }
            override fun close() = Unit
        })
        assertTrue(query.sql.contains("source=? AND kind=?"))
        assertEquals("provider", bindings[1])
        assertEquals("MOVIE", bindings[2])
        assertTrue(bindings.values.contains("%[tmdb:42]%"))
        assertTrue(bindings.values.contains("%100\\%\\_%film%"))
        assertFalse(query.sql.contains("100%_"))
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

    @Test fun aReplacedMatchSplitsAnOldGroupAndReelectsBothRepresentatives() = runTest {
        val store = Store(listOf(item("a", "Film", tmdb = "42").copy(versionKey = "old", isRepresentative = true),
            item("b", "Film", tmdb = "43").copy(versionKey = "old", isRepresentative = false)))
        store.refresher.refresh(listOf("b"))
        assertEquals(2, store.rows.values.map { it.versionKey }.distinct().size)
        assertTrue(store.rows.values.all { it.isRepresentative })
    }
}
