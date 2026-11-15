package dev.lumenara.tv

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveQueryPlanTest {
    @Test fun favouritePagesObserveMarksWithoutNeedingAnItemWrite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "a", "", false, 0, 0))
            dao.save(listOf(Item("a:1", "a", "Channel", "News", "LIVE", "", "", "channel", 1, "")))
            val emissions = Channel<List<String>>(Channel.UNLIMITED)
            val observation = launch {
                dao.livePageAfter("a", "News", "", "favorite", 80, null).collect {
                    emissions.send(it.map(Item::id))
                }
            }
            try {
                assertTrue(withTimeout(5_000) { emissions.receive() }.isEmpty())
                dao.save(Mark("a:1", favorite = true))
                withTimeout(5_000) {
                    while (emissions.receive() != listOf("a:1")) { /* ignore initial invalidations */ }
                }
            } finally { observation.cancel(); observation.join(); emissions.close() }
        } finally { db.close() }
    }

    @Test fun liveFiltersSeekBothWaysUseIndexesAndOpenInThreeStatements() = runBlocking {
        val queries = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .setQueryCallback({ sql, args ->
                if (sql.startsWith("SELECT") && sql.contains("FROM items")) queries += sql to args.toList()
            }, Executor { it.run() }).build()
        try {
            val dao = db.library()
            for (source in listOf("a", "b")) dao.save(Source(source, source, "", false, 0, 0))
            // Equal titles straddle page boundaries; id is the required BINARY tiebreaker.
            val rows = (0 until 401).map { n ->
                val title = "Channel %03d".format(java.util.Locale.ROOT, n / 3)
                Item("a:%04d".format(java.util.Locale.ROOT, n), "a", title,
                    if (n == 400) "Adults" else "News", "LIVE", "", "sealed", title.lowercase(), 1, "")
            }
            dao.save(rows + rows[0].copy(id = "b:0", source = "b"))
            dao.save(Mark(rows[10].id, favorite = true))
            val hidden = listOf("Adults")
            queries.clear()
            assertEquals(400, dao.liveCount("a", "News", "", "", hidden).first())
            val first = dao.livePageAfter("a", "News", "", "", 160, null, hidden = hidden).first()
            val order = dao.liveChannelOrder("a", hidden).first()
            assertEquals(400, order.size)
            assertEquals("A category open must execute at most three item statements", 3, queries.size)

            val next = dao.livePageAfter("a", "News", "", "", 160,
                first.last().let { LiveCursor(it.title, it.id) }, hidden = hidden).first()
            assertEquals(rows.subList(160, 320).map { it.id }, next.map { it.id })
            val previous = dao.livePageAfter("a", "News", "", "", 160,
                next.first().let { LiveCursor(it.title, it.id) }, backwards = true, hidden = hidden).first()
            assertEquals(first.map { it.id }.reversed(), previous.map { it.id })
            assertEquals(320, dao.liveIndex("a", rows[320].id, "News", hidden = hidden))
            assertNull(dao.liveIndex("a", rows[400].id, hidden = hidden))
            assertNull(dao.liveIndex("a", "missing"))
            assertEquals(rows[320].id, dao.livePage("a", "News", "", "", 80, 320, hidden).first().first().id)
            assertEquals(rows[399].id, dao.liveAtOffset("a", 399, hidden)?.id)
            assertNull(dao.liveAtOffset("a", 400, hidden))
            assertEquals(rows[10].id, dao.livePageAfter("a", "News", "channel", "favorite", 80,
                null, hidden = hidden).first().single().id)
            assertEquals(1, dao.liveCount("a", "News", "channel", "favorite", hidden).first())
            assertEquals(0, dao.liveIndex("a", rows[10].id, "News", "channel", "favorite", hidden))

            assertEquals(rows[160].id, dao.liveNeighbors("a", "News", "", "", 80,
                rows[159].id, hidden = hidden).first().first().id)
            assertEquals(rows[159].id, dao.liveNeighbors("a", "News", "", "", 80,
                rows[160].id, backwards = true, hidden = hidden).first().first().id)
            assertTrue(dao.liveNeighbors("a", "", "", "", 80, rows[400].id, hidden = hidden).first().isEmpty())
            assertTrue(dao.liveNeighbors("a", "", "", "", 80, "missing", hidden = hidden).first().isEmpty())
            // Playback holds the old Item title during a catalog refresh. Seek from the DB row.
            dao.save(listOf(rows[159].copy(title = "Z renamed")))
            assertEquals(rows[399].id, dao.liveNeighbors("a", "News", "", "", 80,
                rows[159].id, backwards = true, hidden = hidden).first().first().id)

            // Exercise every optional source/category shape, including P1's new index when present.
            for (source in listOf("", "a")) for (category in listOf("", "News")) {
                dao.livePageAfter(source, category, "", "", 80, LiveCursor(rows[80].title, rows[80].id), hidden = hidden).first()
                dao.livePageAfter(source, category, "channel", "favorite", 80, null, hidden = hidden).first()
                dao.liveCount(source, category, "", "", hidden).first()
                dao.liveIndex(source, rows[10].id, category, hidden = hidden)
                dao.liveChannelOrder(source, hidden).first()
            }
            for ((sql, args) in queries.toList()) {
                val plan = db.openHelper.readableDatabase.query(
                    SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray())
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
                val fullScans = plan.filter { detail ->
                    Regex("\\bSCAN (?:TABLE )?(?:i|a|t|items)(?:\\s|$)").containsMatchIn(detail) &&
                        !detail.contains("USING")
                }
                assertTrue("Unindexed scan: $sql\n$plan", fullScans.isEmpty())
                assertTrue("Missing index: $sql\n$plan", plan.any { it.contains("USING") && it.contains("INDEX") })
                if (sql.contains("ORDER BY")) {
                    assertFalse("Full catalog sort: $sql\n$plan", plan.any { it == "USE TEMP B-TREE FOR ORDER BY" })
                }
            }
        } finally { db.close() }
    }
}
