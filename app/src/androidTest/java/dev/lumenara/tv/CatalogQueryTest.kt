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
            assertEquals(listOf("Film"), dao.categories("MOVIE", "a", safe).first())
            assertEquals(2, dao.shelfCount("a", "MOVIE", "", safe).first())

            // Even old marks/collections stored only on a non-representative remain reachable.
            dao.save(Mark("sub", favorite = true, listed = true, position = 100, duration = 1000, updated = 5))
            for (shelf in listOf("favorite", "list", "resume")) {
                assertEquals("sub", dao.browse("a", "MOVIE", "", "", shelf, 120, hidden = safe).first().single().id)
            }
            assertEquals(1, dao.shelfCount("a", "MOVIE", "favorite", safe).first())
            assertEquals("original", dao.continueWatching("a", safe).first().single().id)
            dao.collection(Member("sub", "Coast"))
            assertEquals("sub", dao.members("a", "Coast", safe).first().single().id)
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
            // Once the dubbed series is expanded it is the preferred card, using sibling history.
            dao.save(listOf(item("dubEp", "Dizi S01E01", kind = "EPISODE").copy(parent = "dub")))
            assertEquals("dub", dao.continueWatching("a").first().single().id)
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
}
