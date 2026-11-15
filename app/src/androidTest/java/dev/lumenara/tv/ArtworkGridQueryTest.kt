package dev.lumenara.tv

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ArtworkGridQueryTest {
    @Test fun playbackWritesDoNotRerunOrdinaryGridButUpdateMarkedShelves() = runBlocking {
        val queries = AtomicInteger()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("SELECT i.* FROM items i") && !sql.contains("marks")) queries.incrementAndGet()
            }, Executor { it.run() }).build()
        val jobs = mutableListOf<Job>()
        try {
            val dao = db.library()
            dao.save(Source("a", "Test", "sealed", false, 0, 0))
            dao.save(listOf(Item("film", "a", "Film", "Film", "MOVIE", "", "sealed", "film", 1, "")))
            val grid = Channel<List<Item>>(Channel.UNLIMITED)
            jobs += launch { dao.browse("a", "MOVIE", "", "", "", 80).collect { grid.send(it) } }
            withTimeout(5000) { grid.receive() }
            val initialQueries = queries.get()
            assertTrue(initialQueries > 0)
            val shelves = listOf("favorite", "list", "resume").map { shelf ->
                Channel<List<Item>>(Channel.UNLIMITED).also { channel ->
                    jobs += launch { dao.browse("a", "MOVIE", "", "", shelf, 80).collect { channel.send(it) } }
                    assertTrue(withTimeout(5000) { channel.receive() }.isEmpty())
                }
            }
            // The first mark and repeated five-second progress writes invalidate marks only.
            repeat(3) { index ->
                dao.save(Mark("film", favorite = true, listed = true,
                    position = (index + 1) * 5000L, duration = 100000, updated = index.toLong()))
                shelves.forEach { channel ->
                    assertEquals("film", withTimeout(5000) { channel.receive() }.single().id)
                }
                // Give the ordinary collector time to execute if it was invalidated as well.
                delay(150)
                assertEquals(initialQueries, queries.get())
                assertTrue(grid.tryReceive().isFailure)
            }
        } finally {
            jobs.forEach { it.cancelAndJoin() }
            db.close()
        }
    }

    @Test fun visibleMarksUsePrimaryKeyAndExcludeOffscreenRows() = runBlocking {
        val captured = AtomicReference<Pair<String, List<Any?>>>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java)
            .setQueryCallback({ sql, args ->
                if (sql.startsWith("SELECT * FROM marks WHERE id IN")) captured.set(sql to args.toList())
            }, Executor { it.run() }).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "Test", "sealed", false, 0, 0))
            dao.save((0 until 100).map {
                Item("$it", "a", "Film $it", "Film", "MOVIE", "", "sealed", "film", 1, "")
            })
            repeat(100) { dao.save(Mark("$it", favorite = true)) }
            assertEquals(setOf("3", "8"), dao.visibleMarks(listOf("3", "8")).first().map { it.id }.toSet())
            val (sql, args) = captured.get()
            val plan = db.openHelper.readableDatabase.query(
                SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray())
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("\n")
            }
            assertTrue(plan, plan.contains("SEARCH") && plan.contains("INDEX"))
            assertFalse(plan, plan.contains("SCAN marks"))
        } finally { db.close() }
    }
}
