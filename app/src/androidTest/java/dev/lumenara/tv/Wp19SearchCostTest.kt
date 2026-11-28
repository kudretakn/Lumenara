package dev.lumenara.tv

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Small deterministic CI fixture. Actual 39,682-row API28 TV p95 remains a lead measurement. */
class Wp19SearchCostTest {
    @Test fun reportsAllocatedBytesBackfillRefreshAndFirstPageCost() = runBlocking {
        withTimeout(60_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "wp19-cost-${System.nanoTime()}.db"
            val captured = AtomicReference<Pair<String, List<Any?>>>()
            val db = Room.databaseBuilder(context, LibraryDb::class.java, name)
                .setQueryCallback({ sql, args ->
                    if (sql.trimStart().startsWith("SELECT i.* FROM items i"))
                        captured.set(sql to args.toList())
                }, Executor { it.run() }).build()
            try {
                val dao = db.library()
                dao.save(Source("s", "Fixture", "sealed", false, 0, 512))
                val rows = (0 until 512).map { index ->
                    Item("s:${index.toString().padStart(4, '0')}", "s", "Film $index", "Film", "MOVIE", "",
                        "sealed", "film $index cafe istanbul 2024 ${if (index % 64 == 0) "needle" else "other"}", 1, "")
                }
                // Explicitly bypass indexing only to create a legacy fixture for backfill cost.
                db.withTransaction { dao.wp19WriteItems(rows) }
                fun scalar(sql: String): Long = db.openHelper.readableDatabase.query(sql).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                fun allocated() = scalar("PRAGMA page_count") * scalar("PRAGMA page_size")
                val bytesBefore = allocated()
                val start = SystemClock.elapsedRealtimeNanos()
                while (Wp19SearchBackfill(db).runBatch()) { }
                val backfillMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                val bytesAdded = allocated() - bytesBefore
                val documents = scalar("SELECT COUNT(*) FROM wp19_search_documents")
                val postings = scalar("SELECT COUNT(*) FROM wp19_search_grams")
                val sequence = scalar("SELECT seq FROM sqlite_sequence WHERE name='wp19_search_documents'")
                val unchangedStart = SystemClock.elapsedRealtimeNanos()
                db.withTransaction { dao.writeBatch(rows) }
                val unchangedMs = (SystemClock.elapsedRealtimeNanos() - unchangedStart) / 1_000_000.0
                assertEquals(sequence, scalar("SELECT seq FROM sqlite_sequence WHERE name='wp19_search_documents'"))
                val changedStart = SystemClock.elapsedRealtimeNanos()
                db.withTransaction { dao.writeBatch(rows.map { it.copy(search = it.search + " changed") }) }
                val changedMs = (SystemClock.elapsedRealtimeNanos() - changedStart) / 1_000_000.0
                val bytesAfterChanged = allocated()
                val walBytes = java.io.File(context.getDatabasePath(name).path + "-wal").length()
                val queryStart = SystemClock.elapsedRealtimeNanos()
                val found = dao.browsePage("s", "MOVIE", "", "needle", "", 20, 0).first()
                val firstPageMs = (SystemClock.elapsedRealtimeNanos() - queryStart) / 1_000_000.0
                assertEquals(8, found.size)
                assertEquals(512L, documents)
                assertTrue(postings > documents)
                // EQP of the actual production page, including all final eligibility predicates.
                val statements = mutableListOf<String>()
                val (query, args) = checkNotNull(captured.get())
                db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $query", args.toTypedArray())).use {
                    while (it.moveToNext()) statements += it.getString(3)
                }
                assertTrue(statements.any { it.contains("sqlite_autoindex_items_1") && it.contains("SEARCH") })
                assertTrue(statements.any { it.contains("wp19_search_grams") && it.contains("gram=?") })
                // Above the capped posting budget, common short words keep the old streaming
                // query and exact count. The fallback still returns only a twenty-row page.
                dao.save(listOf(rows.first().copy(id = "s:extra1"), rows.first().copy(id = "s:extra2")))
                assertEquals(20, dao.browsePage("s", "MOVIE", "", "fi", "", 20, 0).first().size)
                assertFalse(checkNotNull(captured.get()).first.contains("wp19_search_grams"))
                assertEquals(514, dao.browseTotal("s", "MOVIE", "", "fi", "").first())
                Log.i("LumenaraBenchmark", "wp=19 api=${Build.VERSION.SDK_INT} fixtureItems=512 " +
                    "documents=$documents postings=$postings allocatedBytesAdded=$bytesAdded allocatedBytesAfterChanged=$bytesAfterChanged walBytes=$walBytes " +
                    "backfillMs=$backfillMs unchangedWriteMs=$unchangedMs changedWriteMs=$changedMs firstPageMs=$firstPageMs")
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}
