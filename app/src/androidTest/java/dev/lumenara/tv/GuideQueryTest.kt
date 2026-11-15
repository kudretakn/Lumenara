package dev.lumenara.tv

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class GuideQueryTest {
    @Test fun composedPageUsesEndIndexAndSixtyIdleSecondsExecuteNoSql() = runBlocking {
        val statements = AtomicInteger()
        val pageQueries = AtomicInteger()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .setQueryCallback({ sql, _ ->
                statements.incrementAndGet()
                if (sql.contains("INDEXED BY index_programmes_feed_channel_end")) pageQueries.incrementAndGet()
            }, Executor { it.run() }).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val now = System.currentTimeMillis()
            val rows = (0 until 16).map { index ->
                Item("i$index", "s", "Channel $index", "News", "LIVE", "c$index", "", "channel $index", 1, "")
            }
            db.library().save(Source("s", "Test", "", false, 0, rows.size))
            db.library().save(rows)
            db.guide().put(Feed("f", "s", ""))
            // Plenty of unrelated channels: the page must constrain both feed and channel.
            db.guide().slots((0 until 1000).flatMap { channel ->
                listOf(Slot("f", "c$channel", now - 60_000, now + 30_000, "Current", "", "current"),
                    Slot("f", "c$channel", now + 30_000, now + 120_000, "Next", "", "next"))
            })
            val store = GuideStore(db.guide(), scope)
            // Register the same set as a composed lazy-grid page. JVM virtual-time
            // tests assert one batched read; this checks actual Room idle statements.
            val loaded = rows.map { item ->
                MutableStateFlow(emptyList<Slot>()).also { result ->
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        store.cardProgrammes("s", item.id).collect { result.value = it }
                    }
                }
            }
            withTimeout(10_000) { combine(loaded) { page -> page.all { it.size == 2 } }.first { it } }
            assertTrue(pageQueries.get() > 0)
            delay(500) // Let initial Room invalidation/observer bookkeeping settle.
            val before = statements.get()
            // Cards update their current/next selection from these rows without
            // replacing the subscriptions. Include two real 30-second clock ticks.
            repeat(2) {
                delay(30_000)
                val at = System.currentTimeMillis()
                assertTrue(loaded.all { state -> state.value.any { it.start <= at && it.end > at } })
            }
            assertEquals("Idle guide page must execute zero SQL statements", before, statements.get())

            val sql = GUIDE_PAGE_SQL.replace(":feed", "?").replace(":channels", "?,?")
                .replace(":from", "?").replace(":until", "?")
            val plan = db.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                "EXPLAIN QUERY PLAN $sql", arrayOf("f", "c0", "c1", now, now + 120_000),
            )).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("\n")
            }
            assertTrue(plan, plan.contains("index_programmes_feed_channel_end"))
            assertTrue(plan, plan.contains("feed=? AND channel=? AND end>?"))
            assertFalse(plan, plan.contains("SCAN programmes"))
            // Strict boundaries: ended-at-from and starts-at-until slots are excluded.
            assertEquals(listOf("Current"), db.guide().pageSlots("f", listOf("c0"), now, now + 30_000).first().map { it.title })
            assertEquals(listOf("Next"), db.guide().pageSlots("f", listOf("c0"), now + 30_000, now + 120_000).first().map { it.title })
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            db.close()
        }
    }
}
