package dev.lumenara.tv

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.catalog.searchable
import dev.lumenara.data.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveNowQueryTest {
    @Test fun visibleNowUsesOneIndexedQueryAndPreservesGuideMatching() = runBlocking {
        val queries = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, LibraryDb::class.java,
        ).setQueryCallback({ sql, args ->
            if (sql.startsWith("WITH visible")) queries += sql to args.toList()
        }, Executor { it.run() }).build()
        try {
            val now = System.currentTimeMillis()
            val library = db.library()
            val guide = db.guide()
            for (source in listOf("s", "other")) library.save(Source(source, source, "", false, now, 0))
            guide.put(Feed("f", "s", ""))
            guide.put(Feed("foreign", "other", ""))
            fun item(id: String, title: String = id, tvgId: String = id) =
                Item(id, "s", title, "News", "LIVE", tvgId, "", searchable(title), 1, "")
            val visible = listOf(item("direct"), item("fallback", "Named", "missing"),
                item("ambiguous", "Ambiguous", ""), item("mapped"), item("stale"),
                item("crossSource"), item("boundary"))
            library.save(visible)
            fun slot(channel: String, title: String = channel, feed: String = "f",
                start: Long = now - 60_000, end: Long = now + 60_000) =
                Slot(feed, channel, start, end, title, "", searchable(title))
            guide.slots((0 until 10645).map { slot("unrelated-$it") } + listOf(
                slot("direct", "Direct"), slot("direct", "Future", start = now + 60_000, end = now + 120_000),
                slot("named", "Name fallback"), slot("ambiguous-a"), slot("ambiguous-b"),
                slot("mapped"), slot("chosen", "Explicit"), slot("stale"),
                slot("crossSource", feed = "foreign"),
                slot("boundary", "Ended", start = now - 60_000, end = now),
                slot("boundary", "Starts now", start = now),
            ))
            guide.put(listOf(GuideName("f", "named", searchable("Named")),
                GuideName("f", "ambiguous-a", searchable("Ambiguous")),
                GuideName("f", "ambiguous-b", searchable("Ambiguous"))))
            guide.map(Mapping("mapped", "f", "chosen"))
            guide.map(Mapping("stale", "f", "missing"))
            val hits = library.liveNow(visible, now).first().associate { it.itemId to it.slot.title }
            assertEquals(mapOf("direct" to "Direct", "fallback" to "Name fallback",
                "mapped" to "Explicit", "boundary" to "Starts now"), hits)
            assertEquals("One statement for the visible page", 1, queries.size)
            val (sql, args) = queries.single()
            val plan = db.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                "EXPLAIN QUERY PLAN $sql", args.toTypedArray(),
            )).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("\n") }
            assertTrue(plan, plan.contains("index_programmes_feed_channel_end"))
            assertTrue(plan, plan.contains("feed=? AND channel=? AND end>?"))
            assertFalse(plan, Regex("\\bSCAN (?:TABLE )?(?:programmes|p)(?:\\s|$)").containsMatchIn(plan))
            assertFalse(sql, sql.contains("FROM items"))
        } finally { db.close() }
    }
}
