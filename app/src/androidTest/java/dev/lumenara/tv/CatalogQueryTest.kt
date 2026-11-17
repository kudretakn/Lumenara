package dev.lumenara.tv

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogQueryTest {
    @Test fun xtreamMovieCategoryAndAllReturnOneCardWithEveryStreamAvailable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Xtream", "sealed", false, 0, 0))
            val fourK = "TR \u27be 4K Filmler"
            val subtitles = "TR \u27be Altyazılı Filmler"
            val netflix = "TR \u27be Netflix Filmleri"
            val rows = listOf(item("dub-hd", "TR: Avatar (2009) HD Dublaj", fourK, year = ""),
                item("dub-4k", "Avatar 4K Dublaj", fourK, year = ""),
                item("sub", "Avatar [Altyazılı]", subtitles, year = ""),
                item("original", "Avatar FHD", netflix, year = ""),
                item("sequel", "Avatar: Suyun Yolu 4K", fourK, year = ""))
            // Repeated imports upsert the same primary keys rather than multiplying streams.
            dao.save(rows)
            dao.save(rows)
            dao.assignVersions(rows.versionAssignments())
            assertEquals(5, dao.count("a"))
            rows.forEach { dao.save(Mark(it.id, favorite = true)) }
            for (source in listOf("a", "")) {
                for (category in listOf("", fourK)) {
                    val grid = dao.browse(source, "MOVIE", category, "", "", 120).first()
                    assertEquals(setOf("Avatar", "Avatar: Suyun Yolu"), grid.map { it.title }.toSet())
                    assertEquals(2, grid.size)
                    assertEquals(2, dao.browse(source, "MOVIE", category, "", "favorite", 120).first().size)
                }
                for (category in listOf(subtitles, netflix)) {
                    assertEquals("Avatar", dao.browse(source, "MOVIE", category, "", "", 120).first().single().title)
                }
            }
            val card = dao.browse("a", "MOVIE", "", "", "", 120).first().single { it.title == "Avatar" }
            assertEquals(setOf("dub-hd", "dub-4k", "sub", "original"),
                dao.versionRows(card.versionKey).map { it.id }.toSet())
            assertEquals(mapOf(fourK to 2, subtitles to 1, netflix to 1),
                dao.categoryCounts("MOVIE", "a").first().associate { it.category to it.count })
        } finally { db.close() }
    }

    private fun item(id: String, title: String, category: String = "Film", kind: String = "MOVIE",
                     source: String = "a", year: String = "2024", language: String = "tr") =
        Item(id, source, title, category, kind, "", "sealed", title.lowercase(), 1, "poster",
            year = year, genre = "Dram", language = language, rating = "8.0")

    @Test fun filtersChooseTheBestEligibleVersionAndCountsMatchEachCategory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Test", "sealed", false, 0, 0))
            val rows = listOf(item("dub", "Kıyı Dublaj", "Adult"),
                item("original", "Kıyı", language = "en"), item("sub", "Kıyı Altyazılı"),
                item("other", "Başka", year = "1999"), item("episode", "Bölüm", kind = "EPISODE"))
            dao.save(rows)
            dao.assignVersions(rows.versionAssignments())
            val safe = listOf("Adult")
            assertEquals(listOf("dub", "other"), dao.browse("a", "MOVIE", "", "", "", 120).first().map { it.id }.sorted())
            assertEquals("original", dao.browse("a", "MOVIE", "Film", "", "", 120, year = "2024").first().single().id)
            assertEquals("sub", dao.browse("a", "MOVIE", "", "", "", 120,
                year = "2024", genre = "Dram", language = "tr", minRating = 7.5, hidden = safe).first().single().id)
            assertEquals("sub", dao.browse("a", "MOVIE", "", "altyazılı", "", 120, hidden = safe).first().single().id)
            assertTrue(dao.browse("a", "MOVIE", "", "", "", 120, minRating = 9.0).first().isEmpty())
            assertEquals(2, dao.categoryCounts("MOVIE", "a", safe).first().single().count)
            assertEquals(mapOf("Adult" to 1, "Film" to 2), dao.categoryCounts("MOVIE", "a").first().associate { it.category to it.count })
            for (source in listOf("", "a")) for (hidden in listOf(emptyList(), safe)) {
                assertEquals(dao.categoryCounts("MOVIE", source, hidden).first(),
                    dao.sourceCategoryCounts(source, hidden).first().filter { it.kind == "MOVIE" }
                        .map { LiveCategoryCount(it.category, it.count) })
            }
            assertEquals(listOf("Film"), dao.categories("MOVIE", "a", safe).first())
            assertEquals(2, dao.shelfCount("a", "MOVIE", "", safe).first())

            // Even old marks/collections stored only on a non-representative remain reachable.
            dao.save(Mark("sub", favorite = true, listed = true, position = 100, duration = 1000, updated = 5))
            for (shelf in listOf("favorite", "list", "resume")) {
                assertEquals("sub", dao.browse("a", "MOVIE", "", "", shelf, 120, hidden = safe).first().single().id)
            }
            assertEquals(1, dao.shelfCount("a", "MOVIE", "favorite", safe).first())
            assertEquals("sub", dao.continueWatching("a", safe).first().single().id)
            dao.collection(Member("sub", "Coast"))
            assertEquals("sub", dao.members("a", "Coast", safe).first().single().id)
            dao.collection(Member("episode", "Episodes"))
            assertEquals("episode", dao.members("a", "Episodes", safe).first().single().id)
            assertEquals(2, dao.homeFeatures("a", safe).first().size)
            assertTrue(dao.homeFeatures("a", safe).first().none { it.category == "Adult" })
            assertEquals(listOf("original", "other"), dao.suggestions("a", safe).first().map { it.id }.sorted())
        } finally { db.close() }
    }

    @Test fun seriesHistoryOnAnotherVersionSurvivesRepresentativeSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Test", "sealed", false, 0, 0))
            val rows = listOf(item("dub", "Dizi Dublaj", kind = "SERIES"),
                item("sub", "Dizi Altyazılı", kind = "SERIES"),
                item("ep", "Dizi S01E01", kind = "EPISODE").copy(parent = "sub"))
            dao.save(rows)
            dao.assignVersions(rows.versionAssignments())
            dao.save(Mark("ep", position = 10, duration = 100, updated = 1))
            assertEquals("sub", dao.continueWatching("a").first().single().id)
            // Expansion alone must not replace the version with resumable history.
            dao.save(listOf(item("dubEp", "Dizi S01E01", kind = "EPISODE").copy(parent = "dub")))
            assertEquals("sub", dao.continueWatching("a").first().single().id)
            dao.save(Mark("dubEp", position = 10, duration = 100, updated = 2))
            assertEquals("dub", dao.continueWatching("a").first().single().id)
        } finally { db.close() }
    }

    @Test fun refreshReassignsTheWinnerAfterPruningAndKeepsExistingMarks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Test", "sealed", false, 0, 0))
            val old = listOf(item("dub", "Film Dublaj"), item("sub", "Film Altyazılı"))
            dao.save(old)
            dao.assignVersions(old.versionAssignments())
            dao.save(Mark("sub", favorite = true, position = 10, duration = 100, updated = 1))
            val refreshed = listOf(item("sub", "Film Altyazılı"), item("original", "Film"))
                .map { it.copy(generation = 2, search = it.search + " [tmdb:42]") }
            dao.writeBatch(refreshed)
            dao.prune("a", 2)
            dao.orphans("a")
            dao.assignVersions(dao.versionCandidates("a", "MOVIE").map(VersionCandidate::asItem).versionAssignments())
            assertNull(dao.item("dub"))
            assertEquals("original", dao.browse("a", "MOVIE", "", "", "", 120).first().single().id)
            assertEquals("sub", dao.browse("a", "MOVIE", "", "", "favorite", 120).first().single().id)
            assertEquals("a|MOVIE|tmdb:42", dao.item("sub")!!.versionKey)
            assertEquals(10L, dao.mark("sub")!!.position)
        } finally { db.close() }
    }

    @Test fun zeroRatingThresholdStillFallsBackToAnEligibleVersion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Test", "sealed", false, 0, 0))
            val rows = listOf(item("dub", "Film Dublaj").copy(rating = "-1"),
                item("sub", "Film Altyazılı"))
            dao.save(rows)
            dao.assignVersions(rows.versionAssignments())
            assertEquals("sub", dao.browse("a", "MOVIE", "", "", "", 120).first().single().id)
            assertEquals("dub", dao.browse("a", "MOVIE", "", "", "", 120, minRating = -1.0).first().single().id)
        } finally { db.close() }
    }

    @Test fun completedOrUnplayedMovieVersionsDoNotReplaceResumableHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Test", "sealed", false, 0, 0))
            val rows = listOf(item("dub", "Film Dublaj"), item("sub", "Film Altyazılı"))
            dao.save(rows)
            dao.assignVersions(rows.versionAssignments())
            dao.save(Mark("sub", position = 10, duration = 100, updated = 2))
            assertEquals("sub", dao.continueWatching("a").first().single().id)
            dao.save(Mark("dub", position = 100, duration = 100, updated = 3))
            assertEquals("sub", dao.continueWatching("a").first().single().id)
            dao.save(Mark("dub", position = 20, duration = 100, updated = 4))
            assertEquals("dub", dao.continueWatching("a").first().single().id)
            assertTrue(dao.continueWatching("a", listOf("Film")).first().isEmpty())
        } finally { db.close() }
    }

    @Test fun mixedSearchKeepsSortingLimitsAndLiveDedupSeparateFromProductions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            for (source in listOf("a", "b")) dao.save(Source(source, source, "sealed", false, 0, 0))
            val rows = listOf(item("dub", "Film Dublaj"), item("sub", "Film Altyazılı"),
                item("a:channel", "Kanal", kind = "LIVE").copy(tvgId = "channel"),
                item("b:channel", "Kanal", kind = "LIVE", source = "b").copy(tvgId = "channel"),
                item("series", "Dizi", kind = "SERIES"))
            dao.save(rows)
            dao.assignVersions(rows.versionAssignments())
            assertEquals(listOf("series", "dub", "a:channel"),
                dao.browse("", "", "", "", "", 120).first().map { it.id })
            assertEquals(listOf("series", "a:channel"),
                dao.browse("", "", "", "", "recent", 2).first().map { it.id })
            assertEquals("sub", dao.browse("", "", "", "altyazılı", "", 1).first().single().id)
            assertEquals(3, dao.browse("", "", "", "", "popular", 120).first().size)
            dao.save(Mark("sub", position = 10, duration = 100, updated = 5))
            assertEquals("sub", dao.browse("", "", "", "", "resume", 1).first().single().id)
        } finally { db.close() }
    }

    @Test fun onlyMultiSourceLiveBrowsingDeduplicatesProviderChannelIds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            for (source in listOf("a", "b")) dao.save(Source(source, source, "sealed", false, 0, 0))
            val rows = listOf(item("a:shared", "Kanal", kind = "LIVE"),
                item("b:shared", "Kanal", kind = "LIVE", source = "b"),
                item("a:other", "Kanal", kind = "LIVE"))
                .map { it.copy(tvgId = "channel") }
            dao.save(rows + item("movie", "Film"))
            assertEquals(2, dao.browse("a", "LIVE", "", "", "", 120).first().size)
            assertEquals(2, dao.browse("", "LIVE", "", "", "", 120).first().size)
            assertEquals(3, dao.browse("", "", "", "", "", 120).first().size)
            rows.forEach { dao.save(Mark(it.id, favorite = true)) }
            assertEquals(3, dao.browse("", "LIVE", "", "", "favorite", 120).first().size)
        } finally { db.close() }
    }

    @Test fun titleGridUsesStreamingIndexesWithAndWithoutCategoryAndHiddenFilters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captured = AtomicReference<Pair<String, List<Any?>>>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .setQueryCallback({ sql, args ->
                if (sql.trimStart().startsWith("SELECT i.* FROM items i")) captured.set(sql to args.toList())
            }, Executor { it.run() }).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Test", "sealed", false, 0, 0))
            val rows = (0 until 300).map { item("$it", "Film $it", if (it % 2 == 0) "Film" else "Other") }
            dao.save(rows)
            dao.assignVersions(rows.versionAssignments())
            for ((category, hidden) in listOf("" to emptyList(), "Film" to emptyList(), "" to listOf("Adult"))) {
                assertEquals(120, dao.browse("a", "MOVIE", category, "", "", 120, hidden = hidden).first().size)
                val (sql, args) = captured.get()
                assertFalse(sql.contains("GROUP BY"))
                val plan = db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray())).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("\n")
                }
                assertTrue(plan, plan.contains("USING INDEX index_items_"))
                if (category.isNotEmpty() || hidden.isNotEmpty()) assertTrue(plan, plan.contains("index_items_versionKey"))
                // A right-part sort of equal-title IDs is fine; a whole-catalog title sort is not.
                assertFalse(plan, plan.contains("USE TEMP B-TREE FOR ORDER BY"))
                assertFalse(plan, plan.contains("FOR GROUP BY"))
            }
        } finally { db.close() }
    }

    @Test fun allSourceCountsCategoriesAndSmallCategoryGridsUseCategoryIndexes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captured = AtomicReference<Pair<String, List<Any?>>>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .setQueryCallback({ sql, args ->
                if (sql.trimStart().startsWith("SELECT i.kind,i.category,") ||
                    sql.trimStart().startsWith("SELECT i.category,") ||
                    sql.trimStart().startsWith("SELECT DISTINCT i.category") ||
                    sql.trimStart().startsWith("SELECT i.* FROM items i")) captured.set(sql to args.toList())
            }, Executor { it.run() }).build()
        try {
            val dao = db.library()
            for (source in listOf("a", "b")) dao.save(Source(source, source, "sealed", false, 0, 0))
            val kinds = listOf("LIVE", "MOVIE", "SERIES")
            val rows = kinds.flatMap { kind ->
                (0 until 2000).map { item("$kind:$it", "$kind title $it", kind = kind) } +
                    (0 until 5).map { item("$kind:rare:$it", "$kind rare $it", "Rare", kind,
                        source = if (it < 3) "a" else "b") }
            }
            dao.save(rows)
            dao.assignVersions(rows.versionAssignments())
            fun assertCategoryPlan(source: String) {
                val (sql, args) = captured.get()
                val plan = db.openHelper.readableDatabase.query(
                    SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray())
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("\n")
                }
                val index = if (source.isEmpty()) "index_items_kind_category_title"
                    else "index_items_source_kind_category_title"
                assertTrue(plan, plan.contains(index))
                assertFalse(plan, Regex("\\bSCAN (?:TABLE )?(?:items|i)(?:\\s|$)").containsMatchIn(plan))
                assertFalse(plan, plan.contains("TEMP B-TREE FOR GROUP BY"))
                assertFalse(plan, plan.contains("USE TEMP B-TREE FOR ORDER BY"))
            }
            for (source in listOf("", "a")) {
                val allGroups = dao.sourceCategoryCounts(source, listOf("Adult")).first()
                assertEquals(6, allGroups.size)
                assertCategoryPlan(source)
                for (kind in kinds) {
                    val groups = dao.categoryCounts(kind, source, listOf("Adult")).first()
                    assertEquals(groups, allGroups.filter { it.kind == kind }.map { LiveCategoryCount(it.category, it.count) })
                    assertEquals(2000, groups.single { it.category == "Film" }.count)
                    assertEquals(if (source.isEmpty()) 5 else 3, groups.single { it.category == "Rare" }.count)
                    assertCategoryPlan(source)
                    assertEquals(listOf("Film", "Rare"), dao.categories(kind, source, listOf("Adult")).first())
                    assertCategoryPlan(source)
                    // LIVE retains its multi-source dedup policy; movie/series grids never GROUP BY.
                    if (kind != "LIVE") for (category in listOf("Film", "Rare")) {
                        val grid = dao.browse(source, kind, category, "", "", 120, hidden = listOf("Adult")).first()
                        assertEquals(if (category == "Film") 120 else if (source.isEmpty()) 5 else 3, grid.size)
                        assertCategoryPlan(source)
                    }
                }
            }
        } finally { db.close() }
    }

    @Test fun visibilityNamesOnlyReadLiveMovieAndSeriesWithoutVersionCounting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captured = AtomicReference<Pair<String, List<Any?>>>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .setQueryCallback({ sql, args ->
                if (sql.trimStart().startsWith("SELECT DISTINCT category FROM items")) captured.set(sql to args.toList())
            }, Executor { it.run() }).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Test", "sealed", false, 0, 0))
            dao.save(listOf(item("live", "Live", "News", "LIVE"),
                item("movie", "Movie", "Film"), item("series", "Series", "Film", "SERIES"),
                item("hidden", "Adult movie", "For Adults"),
                item("episode", "Episode", "Episode-only", "EPISODE")))
            assertEquals(listOf("Film", "For Adults", "News"), dao.categoryNames().first())
            val (sql, args) = captured.get()
            assertFalse(sql, sql.contains("COUNT(") || sql.contains("versionKey"))
            val plan = db.openHelper.readableDatabase.query(
                SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray())
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("\n")
            }
            assertTrue(plan, plan.contains("COVERING INDEX index_items_kind_category_title"))
        } finally { db.close() }
    }
}
