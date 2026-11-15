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

class SeriesQueryTest {
    @Test fun episodeQueriesProbeTheParentAndNeverScanTheCatalog() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captured = AtomicReference<Pair<String, List<Any?>>>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .setQueryCallback({ sql, args ->
                if (sql.contains("SELECT * FROM items WHERE parent=")) captured.set(sql to args.toList())
            }, Executor { it.run() }).build()
        try {
            val dao = db.library()
            dao.save(Source("source", "Test", "sealed", false, 0, 0))
            fun item(id: String, category: String, parent: String = "") =
                Item(id, "source", id, category, if (parent.isEmpty()) "SERIES" else "EPISODE",
                    "", "sealed", id, 1, "", parent = parent)
            dao.save(listOf(item("series", "Safe"), item("hidden-series", "Adult"),
                item("first", "Season 1", "series"), item("hidden-episode", "Adult", "series"),
                item("hidden-parent-episode", "Season 1", "hidden-series")))
            for (hidden in listOf(emptyList(), listOf("Adult"), listOf("Adult", "Other"))) {
                for (snapshot in listOf(false, true)) {
                    val episodes = if (snapshot) dao.episodeSnapshot("series", hidden)
                        else dao.episodes("series", hidden).first()
                    assertEquals(if (hidden.isEmpty()) 2 else 1, episodes.size)
                    val (sql, args) = captured.get()
                    val plan = db.openHelper.readableDatabase.query(
                        SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray())
                    ).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("\n")
                    }
                    assertTrue(plan, plan.contains("index_items_parent"))
                    assertFalse(plan, Regex("\\bSCAN items\\b").containsMatchIn(plan))
                    // IN () is constant false: some SQLite versions print a dead SCAN s
                    // branch for the empty hidden list. Nonempty lists must probe the PK.
                    if (hidden.isNotEmpty()) {
                        assertTrue(plan, plan.contains("SEARCH s USING INDEX sqlite_autoindex_items_1"))
                        assertFalse(plan, Regex("\\bSCAN s\\b").containsMatchIn(plan))
                    }
                }
            }
            assertTrue(dao.episodeSnapshot("hidden-series", listOf("Adult")).isEmpty())
            assertTrue(dao.episodes("hidden-series", listOf("Adult")).first().isEmpty())
            assertTrue(dao.episodeSnapshot("missing", listOf("Adult")).isEmpty())
            dao.save(Mark("first", position = 100, duration = 1000, updated = 1))
            dao.save(Mark("hidden-episode", favorite = true))
            assertEquals(listOf("first"), dao.marksByIds(listOf("first", "missing")).map { it.id })
        } finally { db.close() }
    }
}
