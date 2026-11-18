package dev.lumenara.tv

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

class CatalogDerivedTest {
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
            assertEquals(1000, db.derived().totals("", false).first().single().count)
            assertEquals(1000, db.library().sourceCategoryCounts("").first().single().count)
            db.library().adultCategoryNames().first()
            assertTrue(queries.joinToString("\n"), queries.none {
                Regex("(?i)\\b(?:FROM|JOIN)\\s+(?:items|version_backfill_rows)\\b").containsMatchIn(it)
            })
        } finally { db.close() }
    }
}
