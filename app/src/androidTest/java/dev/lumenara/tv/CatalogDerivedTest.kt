package dev.lumenara.tv

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

internal suspend fun prepareTestCatalog(db: LibraryDb) {
    for (source in db.library().snapshot()) {
        val work = CatalogDerivedBackfill(db)
        work.reset(source.id)
        while (work.runBatch(source.id)) { }
    }
}

/** Direct DAO fixtures must publish the same derived data as a repository import.
 * Use the repository's Room instance so its active flows receive invalidations too.
 * Reflection stays in androidTest; production must not expose a fixture-only DB API.
 */
internal suspend fun prepareTestCatalog(repo: Repository, source: String) {
    val field = Repository::class.java.getDeclaredField("db").apply { isAccessible = true }
    val db = field.get(repo) as LibraryDb
    db.withTransaction {
        val work = CatalogDerivedBackfill(db)
        work.reset(source)
        while (work.runBatch(source)) { }
    }
}

class CatalogDerivedTest {
    @Test fun pendingDerivedCatalogReturnsContentUntilTheLastBatchCommits() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, LibraryDb::class.java).build()
        try {
            assertTrue(db.derived().ready("").first())
            db.library().save(Source("pending", "Test", "", false, System.currentTimeMillis(), 1))
            db.library().save(listOf(Item("channel", "pending", "Channel", "News", "LIVE", "", "", "channel", 1, "")))
            assertFalse(db.derived().ready("pending").first())
            assertFalse(db.derived().ready("").first())
            assertTrue(db.derived().ready("another-source").first())
            assertEquals(1, db.library().liveCount("pending", "", "", "").first())
            assertEquals("channel", db.library().livePage("pending", "", "", "", 10, 0).first().single().id)
            assertEquals(1, db.library().sourceCategoryCounts("pending").first().single().count)
            val work = CatalogDerivedBackfill(db)
            while (work.runBatch("pending", 1)) {
                assertFalse(db.derived().ready("pending").first())
            }
            assertTrue(db.derived().ready("pending").first())
            assertEquals(1, db.library().liveCount("pending", "", "", "").first())
        } finally { db.close() }
    }

    @Test fun migrationRowsStayBrowsableSearchableCountedAndNumberedThroughoutPartialBackfill() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Pending", "", false, 0, 0))
            dao.save(Source("b", "Ready", "", false, 0, 0))
            fun row(id: String, title: String, category: String, kind: String) =
                Item(id, "a", title, category, kind, "", "", title.lowercase(), 1, "",
                    cleanTitle = "", sortKey = "", isAdult = false)
            val rows = listOf(row("z-live", "Z News", "News", "LIVE"),
                row("hidden-live", "Hidden", "XXX", "LIVE"), row("a-live", "A News", "News", "LIVE"),
                row("dub", "Coast Dublaj", "XXX", "MOVIE"), row("sub", "Coast Altyazılı", "Film", "MOVIE"),
                row("other", "Another Film", "Film", "MOVIE"))
            dao.save(rows)
            dao.assignVersions(rows.versionAssignments())
            dao.save(listOf(row("ready", "Ready News", "News", "LIVE").copy(source = "b")))
            val work = CatalogDerivedBackfill(db)
            while (work.runBatch("b", 1)) { }
            dao.save(Mark("sub", favorite = true, listed = true))

            suspend fun verifyPending() {
                assertFalse(dao.catalogReady("a").first())
                assertTrue(dao.catalogReady("b").first())
                val hidden = dao.visibleAdultCategoryNames().first()
                assertEquals(listOf("XXX"), hidden)
                for (source in listOf("a", "")) {
                    val movies = dao.browsePage(source, "MOVIE", "", "", "", 20, 0, hidden = hidden).first()
                    assertEquals(listOf("other", "sub"), movies.map { it.id })
                    assertEquals(2, dao.browseTotal(source, "MOVIE", "", "", "", hidden = hidden).first())
                    assertEquals(1, dao.browseIndex(source, "MOVIE", "", "", "", "sub", hidden = hidden))
                    assertEquals(listOf("MOVIE", "MOVIE"), dao.browseKinds(source, "MOVIE", "", "", "", hidden = hidden).first())
                    assertEquals("sub", dao.browse(source, "MOVIE", "", "coast", "", 20, hidden = hidden).first().single().id)
                    assertEquals("sub", dao.browse(source, "MOVIE", "", "", "favorite", 20, hidden = hidden).first().single().id)
                    assertEquals(2, dao.categoryCounts("MOVIE", source, hidden).first().single().count)
                    assertEquals(2, dao.fallbackHubTotals(source, hidden).first().single { it.kind == "MOVIE" }.count)
                    assertEquals(if (source.isEmpty()) 3 else 2,
                        dao.browse(source, "", "", "news", "", 20, hidden = hidden).first().size)
                    assertEquals(3, dao.categoryCounts("MOVIE", source).first().sumOf { it.count })
                }
                val live = dao.livePage("a", "", "", "", 20, 0, hidden).first()
                assertEquals(listOf("z-live", "a-live"), live.map { it.id })
                assertEquals(2, dao.liveCount("a", "", "", "", hidden).first())
                assertEquals(1, dao.liveCount("a", "", "a news", "", hidden).first())
                assertEquals(1, dao.liveIndex("a", "a-live", hidden = hidden))
                assertEquals("a-live", dao.livePageAfter("a", "", "", "", 1,
                    LiveCursor(live.first().sortKey, "z-live"), hidden = hidden).first().single().id)
                assertEquals("z-live", dao.liveNeighbors("a", "", "", "", 1, "a-live", true, hidden).first().single().id)
                assertEquals("a-live", dao.livePage("a", "", "", "", 1, 1, hidden).first().single().id)
                assertEquals(live.map { it.id }, dao.liveChannelOrder("a", hidden).first().map { it.id })
                assertEquals(listOf(1, 2), dao.providerChannelNumbers("a", live.map { it.id }, hidden)
                    .sortedBy { it.safeChannelNumber }.map { it.safeChannelNumber })
                assertEquals("a-live", dao.providerChannel("a", 1, hidden)!!.id)
            }
            verifyPending()
            // Keep the same subscription alive across the final state transition.
            val completedOrder = async {
                withTimeout(30_000) {
                    dao.livePage("a", "", "", "", 20, 0, listOf("XXX"))
                        .first { it.map { row -> row.id } == listOf("a-live", "z-live") }
                }
            }
            while (db.derived().state("a")?.phase != "done") {
                work.runBatch("a", 1)
                if (db.derived().state("a")?.phase != "done") verifyPending()
            }
            assertEquals(listOf("a-live", "z-live"), completedOrder.await().map { it.id })
            assertEquals(2, dao.liveCount("a", "", "", "", listOf("XXX")).first())
            assertEquals(2, db.derived().totals("a", false).first().single { it.kind == "MOVIE" }.count)
        } finally { db.close() }
    }

    @Test fun resumableBatchesKeepVisibilityTotalsCategoryCountsAndNumbersAcrossReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "catalog-derived-resume"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, LibraryDb::class.java, name).build()
        var db = open()
        try {
            db.library().save(Source("a", "Test", "sealed", false, 0, 0))
            fun row(id: String, title: String, category: String, kind: String) =
                Item(id, "a", title, category, kind, "", "sealed", title, 1, "")
            val rows = listOf(row("dub", "Coast Dublaj", "XXX", "MOVIE"),
                row("sub", "Coast Altyazılı", "TR Film", "MOVIE"),
                row("live-b", "B", "News", "LIVE"), row("live-a", "A", "XXX", "LIVE"),
                row("live-c", "C", "TR News", "LIVE"))
            db.library().save(rows)
            db.library().assignVersions(rows.versionAssignments())
            db.library().save(Mark("sub", position = 10, duration = 100, updated = 1))
            val phases = mutableSetOf<String>()
            while (db.derived().state("a")?.phase != "done") {
                val before = db.derived().state("a")
                try {
                    db.withTransaction {
                        CatalogDerivedBackfill(db).runBatch("a", 1)
                        throw CancellationException("rollback")
                    }
                } catch (_: CancellationException) { }
                assertEquals(before, db.derived().state("a"))
                CatalogDerivedBackfill(db).runBatch("a", 1)
                val state = db.derived().state("a")!!
                if (phases.add(state.phase)) {
                    db.close()
                    db = open()
                    assertEquals(state, db.derived().state("a"))
                }
            }
            assertNull(db.derived().pendingSource())
            assertEquals(1, db.derived().totals("a", false).first().single { it.kind == "MOVIE" }.count)
            assertEquals(1, db.derived().totals("a", true).first().single { it.kind == "MOVIE" }.count)
            assertEquals(2, db.derived().totals("a", false).first().single { it.kind == "LIVE" }.count)
            assertEquals(listOf("TR Film", "XXX"), db.library().categoryCounts("MOVIE", "a").first().map { it.category })
            assertEquals("Coast", db.library().item("sub")!!.cleanTitle)
            assertEquals("live-a", db.derived().channel("a", 1)!!.id)
            assertEquals("live-b", db.derived().safeChannel("a", 1)!!.id)
            assertEquals("live-c", db.derived().safeChannel("a", 2)!!.id)
            assertEquals(10L, db.library().mark("sub")!!.position)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun completedCatalogStartupReadsNeverTouchItemsOrRecheckBackfillRows() = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .setQueryCallback({ sql, _ -> queries += sql }, Executor { it.run() }).build()
        try {
            db.library().save(Source("a", "Test", "sealed", false, 0, 0))
            db.library().save((0 until 1000).map { n -> Item("$n", "a", "Channel $n", "TR News",
                "LIVE", "", "sealed", "channel $n", 1, "") })
            prepareTestCatalog(db)
            queries.clear()
            assertNull(db.derived().pendingSource())
            assertTrue(db.derived().ready("").first())
            assertEquals(1000, db.derived().totals("", false).first().single().count)
            assertEquals(1000, db.library().sourceCategoryCounts("").first().single().count)
            db.library().adultCategoryNames().first()
            assertTrue(queries.joinToString("\n"), queries.none {
                Regex("(?i)\\b(?:FROM|JOIN)\\s+(?:items|version_backfill_rows)\\b").containsMatchIn(it)
            })
        } finally { db.close() }
    }
}
