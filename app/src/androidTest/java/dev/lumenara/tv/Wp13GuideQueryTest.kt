package dev.lumenara.tv

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.catalog.searchable
import dev.lumenara.data.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class Wp13GuideQueryTest {
    @Test fun searchFindsVisibleHitsAfterFourHundredRejectedRowsAndEqualsReference() = runBlocking {
        val statements = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, LibraryDb::class.java,
        ).setQueryCallback({ sql, args ->
            if (sql.startsWith("WITH eligible")) statements += sql to args.toList()
        }, Executor { it.run() }).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val now = System.currentTimeMillis()
            val from = now - 300_000
            val sources = mapOf("f" to "s", "f2" to "s", "foreign" to "other")
            val items = (0 until 205).map { item("hidden$it", category = "Adult") } +
                (0 until 325).map { item("v%03d".format(it)) } + listOf(
                    item("manual"), item("fallback", "İsim", "missing"), item("ambiguous", "Shared", ""),
                    item("stale"), item("cross"), item("other", tvg = "v000", source = "other"),
                    item("alias", tvg = "v000"),
                )
            fun slot(channel: String, start: Long = now - 1000, end: Long = now + 1000,
                title: String = "Current", feed: String = "f") =
                Slot(feed, channel, start, end, title, "", searchable(title))
            val slots = (0 until 205).flatMap {
                listOf(slot("hidden$it", now - 60_000), slot("orphan$it", now - 60_000))
            } + (0 until 325).flatMap {
                val id = "v%03d".format(it)
                listOf(slot(id), slot(id, now + 1000, now + 2000, "Future"),
                    slot(id, now - 3000, now, "Past"))
            } + listOf(slot("chosen"), slot("manual"), slot("named"), slot("amb-a"), slot("amb-b"),
                slot("stale"), slot("cross"), slot("v000", feed = "f2"), slot("v000", feed = "foreign"),
                slot("expired", from - 1000, from))
            val names = listOf(GuideName("f", "named", searchable("İsim")),
                GuideName("f", "amb-a", "shared"), GuideName("f", "amb-b", "shared"),
                // Direct ID presence must suppress this fallback even for a future-only search.
                GuideName("f", "named", searchable("v000")))
            val mappings = listOf(Mapping("manual", "f", "chosen"), Mapping("stale", "f", "absent"),
                Mapping("cross", "foreign", "v000"))
            db.withTransaction {
                for (source in sources.values.distinct()) db.library().save(Source(source, source, "", false, now, 0))
                db.library().save(items)
                sources.forEach { (feed, source) -> db.guide().put(Feed(feed, source, "")) }
                db.guide().put(names)
                db.guide().slots(slots)
                mappings.forEach { db.guide().map(it) }
            }
            val hidden = MutableStateFlow(listOf("Adult"))
            val store = GuideStore(db.guide(), scope, hidden)
            fun reference(source: String) = GuideResolver(items.filter { source.isEmpty() || it.source == source }
                .map { GuideChannel(it.id, it.title, it.tvgId, it.search, it.source, it.category) },
                mappings, names, slots.map { ProgrammeChannel(it.feed, it.channel, sources.getValue(it.feed)) }.toSet())
            val rejected = slots.filter { it.start <= now && it.end > now }.sortedBy { it.start }.take(400)
            assertEquals(400, rejected.size)
            assertTrue(reference("s").searchPrograms(rejected, "", from, now, hidden.value).isEmpty())
            for (source in listOf("s", "")) for (query in listOf("", "CURRENT", "Future", "Past", "absent")) {
                val expected = reference(source).searchPrograms(slots, query, from, now, hidden.value)
                val actual = withTimeout(5000) { store.searchPrograms(source, query, flowOf(from to now)).first() }
                assertEquals("sourceAll=${source.isEmpty()} query=$query", expected, actual)
            }
            // Keep a subscription alive while visibility changes, including the empty-target case.
            val results = kotlinx.coroutines.channels.Channel<List<ProgramHit>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val job = scope.launch {
                store.searchPrograms("s", "", flowOf(from to now)).collect { results.send(it) }
            }
            try {
                assertEquals(reference("s").searchPrograms(slots, "", from, now, hidden.value),
                    withTimeout(5000) { results.receive() })
                hidden.value = listOf("Adult", "News")
                assertTrue(withTimeout(5000) { results.receive() }.isEmpty())
                hidden.value = emptyList()
                assertEquals(reference("s").searchPrograms(slots, "", from, now),
                    withTimeout(5000) { results.receive() })
            } finally { job.cancelAndJoin(); results.close() }
            assertTrue(statements.size >= 2) // More than 300 eligible targets exercise the global merge.
            for ((sql, args) in statements) {
                assertTrue(args.size <= 999)
                assertProgrammeRangePlan(db, sql, args)
            }
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            db.close()
        }
    }

    @Test fun largeGuideVisiblePageUsesNameIndexMatchesReferenceAndHasSub100msP95() = runBlocking {
        val statements = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, LibraryDb::class.java,
        ).setQueryCallback({ sql, args ->
            if (sql.startsWith("WITH visible") || sql.startsWith("SELECT * FROM programmes INDEXED") ||
                sql.contains("COUNT(DISTINCT channel)")) statements += sql to args.toList()
        }, Executor { it.run() }).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val now = System.currentTimeMillis()
            val visible = (0 until 28).map { item("i$it", "İsim $it", "absent") }
            val allSlots = (0 until 10_645).flatMap { channel ->
                (0 until 4).map { offset -> Slot("f", "c$channel", now - 1000 + offset * 60_000,
                    now + 59_000 + offset * 60_000, "Programme $offset", "", "programme $offset") }
            }
            val names = (0 until 10_645).flatMap { channel ->
                listOf(GuideName("f", "c$channel", searchable("İsim $channel")),
                    GuideName("f", "c$channel", "alias $channel"), GuideName("f", "c$channel", "other $channel"))
            }
            db.withTransaction {
                db.library().save(Source("s", "Fixture", "", false, now, 0))
                db.library().save(visible)
                db.guide().put(Feed("f", "s", ""))
                names.chunked(256).forEach { db.guide().put(it) }
                allSlots.chunked(256).forEach { db.guide().slots(it) }
            }
            val resolver = GuideResolver(visible.map { GuideChannel(it.id, it.title, it.tvgId, source = "s") },
                emptyList(), names, allSlots.map { ProgrammeChannel(it.feed, it.channel, "s") }.toSet())
            val store = GuideStore(db.guide(), scope, clock = { now })
            val actual = withTimeout(5000) {
                combine(visible.map { store.cardProgrammes("s", it.id) }) { it.toList() }.first { page ->
                    page.all { it.size == 4 }
                }
            }
            visible.forEachIndexed { index, item ->
                val expected = allSlots.filter { item.id in resolver.itemIds(it) && it.end > now }
                    .sortedWith(compareBy<Slot> { it.start }.thenBy { it.feed })
                assertEquals(expected, actual[index])
            }
            val targetQuery = statements.first { it.first.startsWith("WITH visible") && it.second.size == 112 }
            val pageQuery = statements.first {
                it.first.startsWith("SELECT * FROM programmes INDEXED") && it.second.size == 31
            }
            // Cover all three production name-fallback paths, not a synthetic standalone probe.
            assertEquals(28, db.library().liveNow(visible, now).first().size)
            assertEquals(4, db.guide().guide("s", "absent", searchable("İsim 0"), now, now + 300_000, "").first().size)
            statements.filter { it.first.contains("COUNT(DISTINCT") }.forEach { (sql, args) ->
                val plan = plan(db, sql, args)
                assertTrue(plan, plan.contains("index_epgNames_feed_name_channel"))
                assertTrue(plan, plan.contains("feed=? AND name=?"))
            }
            assertProgrammeRangePlan(db, pageQuery.first, pageQuery.second)
            // Time the exact page-local target and programme SQL, consuming every returned column.
            // Coroutine debounce/rendering is outside this query budget and needs TV measurement.
            fun readPage() {
                for ((sql, args) in listOf(targetQuery, pageQuery)) {
                    db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { cursor ->
                        var count = 0
                        while (cursor.moveToNext()) {
                            for (column in 0 until cursor.columnCount) cursor.getString(column)
                            count++
                        }
                        assertEquals(if (sql.startsWith("WITH visible")) 28 else 112, count)
                    }
                }
            }
            repeat(3) { readPage() }
            val samples = (0 until 20).map {
                val start = SystemClock.elapsedRealtimeNanos()
                readPage()
                (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }.sorted()
            Log.i("LumenaraBenchmark", "wp=13 api=${Build.VERSION.SDK_INT} channels=10645 programmes=42580 " +
                "names=31935 visible=28 samples=20 pageP50Ms=${samples[9]} pageP95Ms=${samples[18]} pageMaxMs=${samples.last()}")
            assertTrue("Visible guide SQL p95=${samples[18]}ms; acceptance is <100ms", samples[18] < 100.0)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            db.close()
        }
    }

    private fun item(id: String, title: String = id, tvg: String = id, category: String = "News", source: String = "s") =
        Item(id, source, title, category, "LIVE", tvg, "", searchable(title), 1, "")

    private fun plan(db: LibraryDb, sql: String, args: List<Any?>): String =
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("\n")
        }

    private fun assertProgrammeRangePlan(db: LibraryDb, sql: String, args: List<Any?>) {
        val plan = plan(db, sql, args)
        assertTrue(plan, plan.contains("index_programmes_feed_channel_end"))
        assertTrue(plan, plan.contains("feed=? AND channel=? AND end>?"))
        assertFalse(plan, Regex("\\bSCAN (?:TABLE )?(?:programmes|p)(?:\\s|$)").containsMatchIn(plan))
    }
}
