package dev.lumenara.tv

import android.os.SystemClock
import android.util.Log
import android.os.Build
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Item
import dev.lumenara.data.LibraryDb
import dev.lumenara.data.Source
import dev.lumenara.data.Repository
import dev.lumenara.data.SourceSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LargeCatalogTest {
    @Test
    fun preparedCountsHaveSubFiftyMillisecondP95With39682Items() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "wp06-count-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, LibraryDb::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val dao = db.library()
            db.withTransaction {
                for (source in listOf("a", "b")) dao.save(Source(source, source, "sealed", false, 0, 0))
                for (start in 0 until 39_682 step 256) {
                    dao.save((start until minOf(start + 256, 39_682)).map { id ->
                        val production = id / 3
                        val rank = id % 3
                        val source = if (production % 2 == 0) "a" else "b"
                        val kind = if (production % 4 < 2) "MOVIE" else "SERIES"
                        val category = if (rank == 0 && production % 5 == 0) "Adult"
                            else if (rank == 2) "Alternate" else "Film"
                        Item("$source:$id", source, "Production $production", category, kind, "", "sealed",
                            "production $production", 1, "", rating = if (rank == 2) "8.0" else "",
                            versionKey = "$source|$kind|wp06:$production", isRepresentative = rank == 0,
                            versionRank = rank)
                    })
                }
                prepareTestCatalog(db)
            }
            assertEquals(39_682, dao.count("a") + dao.count("b"))
            for (source in listOf("", "a")) for (kind in listOf("MOVIE", "SERIES")) {
                for ((category, hidden) in listOf("" to emptyList(), "" to listOf("Adult"), "Film" to emptyList())) {
                    val expected = dao.browse(source, kind, category, "", "", Int.MAX_VALUE, hidden = hidden).first().size
                    suspend fun total() = dao.browseTotal(source, kind, category, "", "", hidden = hidden).first()
                    repeat(5) { assertEquals(expected, total()) }
                    val samples = (0 until 40).map {
                        val start = SystemClock.elapsedRealtimeNanos()
                        assertEquals(expected, total())
                        (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    }.sorted()
                    val p50 = samples[19]
                    val p95 = samples[37] // nearest rank: ceil(40 * .95) - 1
                    Log.i("LumenaraBenchmark", "wp=06 rows=39682 api=${Build.VERSION.SDK_INT} " +
                        "sourceAll=${source.isEmpty()} kind=$kind categorySelected=${category.isNotEmpty()} " +
                        "adultHidden=${hidden.isNotEmpty()} count=$expected samples=40 " +
                        "countP50Ms=$p50 countP95Ms=$p95 countMaxMs=${samples.last()}")
                    assertTrue("Prepared count p95=${p95}ms; API28 acceptance is <50ms", p95 < 50.0)
                }
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun homeAndMovieShelvesStayFastWithThirtyThousandMovies() = runBlocking {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = "Test · 30000 movies · ${System.nanoTime()}"
        MockWebServer().use { server ->
            server.start()
            val list = buildString {
                appendLine("#EXTM3U")
                repeat(30000) { id ->
                    val suffix = when (id % 3) {
                        1 -> " (Dublaj)"
                        2 -> " (Altyazılı)"
                        else -> ""
                    }
                    appendLine("#EXTINF:-1 group-title=\"Film\" tvg-logo=\"https://example.invalid/poster/$id.jpg\",Film ${id / 3} Hikayesi$suffix")
                    appendLine("https://example.invalid/movie/$id.mp4")
                }
            }
            server.enqueue(MockResponse().setBody(list))
            try {
                repo.add(name, SourceSpec(server.url("/list").toString(), true))
                val source = repo.library.snapshot().single { it.name == name }
                assertEquals(30000, source.count)

                // M3U imports have no ratings; seed a bounded set for the popular shelf.
                val rated = repo.library.browseRows(source.id, "MOVIE", "", "", "recent", 96).first()
                repo.library.save(rated.map { it.copy(rating = "8.0") })

                suspend fun measure(label: String, query: suspend () -> List<Item>): List<Item> {
                    val start = SystemClock.elapsedRealtime()
                    val result = query()
                    val elapsedMs = SystemClock.elapsedRealtime() - start
                    Log.i("LumenaraBenchmark", "rows=30000 shelf=$label queryMs=$elapsedMs cards=${result.size}")
                    assertTrue("$label returned no cards", result.isNotEmpty())
                    assertTrue("$label took ${elapsedMs}ms (limit 10000ms)", elapsedMs < 10000L)
                    return result
                }

                measure("homeFeatures") { repo.library.homeFeatures(source.id).first() }
                val recent = measure("recent") {
                    repo.library.browse(source.id, "MOVIE", "", "", "recent", 24).first()
                }
                measure("popular") {
                    repo.library.browse(source.id, "MOVIE", "", "", "popular", 24).first()
                }
                val movies = measure("movies") {
                    repo.library.browse(source.id, "MOVIE", "", "", "", 120).first()
                }
                assertEquals(120, movies.size)
                assertEquals(120, movies.map { it.title }.distinct().size)
                val category = measure("movieCategory") {
                    repo.library.browse(source.id, "MOVIE", "Film", "", "", 120).first()
                }
                assertEquals(120, category.size)
                assertEquals(10000, repo.library.categoryCounts("MOVIE", source.id).first().single().count)
                assertEquals(10000, repo.library.shelfCount(source.id, "MOVIE", "").first())
                assertEquals(24, recent.size)
                assertEquals(recent.size, recent.map { it.title }.distinct().size)
                assertTrue("Version suffixes should be merged", recent.all {
                    !it.title.contains("(Dublaj)") && !it.title.contains("(Altyazılı)")
                })
            } finally {
                repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
            }
        }
        Unit
    }

    @Test
    fun importsAndSearchesTenThousandChannels() = runBlocking {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val prefs = dev.lumenara.data.Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val previousAdult = prefs.showAdult.first()
        val name = "Test · Büyük katalog"
        MockWebServer().use { server ->
            server.start()
            val list = buildString {
                appendLine("#EXTM3U")
                repeat(10000) { id ->
                    appendLine("#EXTINF:-1 group-title=\"Haber\",Yerel Kanal $id")
                    appendLine("https://example.invalid/live/$id")
                }
            }
            server.enqueue(MockResponse().setBody(list))
            try {
                prefs.setShowAdult(false)
                val start = SystemClock.elapsedRealtime()
                repo.add(name, SourceSpec(server.url("/list").toString(), true))
                val importMs = SystemClock.elapsedRealtime() - start
                val source = repo.library.snapshot().single { it.name == name }
                assertEquals(10000, source.count)
                val queryStart = SystemClock.elapsedRealtime()
                val result =
                    repo.library.browse(source.id, "LIVE", "", "kanal 9999", "", 80).first()
                val queryMs = SystemClock.elapsedRealtime() - queryStart
                assertEquals("Yerel Kanal 9999", result.single().title)
                val numberStart = SystemClock.elapsedRealtime()
                val numbered = repo.liveChannel(9999, source.id)
                val numberMs = SystemClock.elapsedRealtime() - numberStart
                val orderedTitles = (0 until 10000).map { "Yerel Kanal $it" }.sorted()
                assertEquals(orderedTitles[9998], numbered?.title)
                assertTrue("Channel 9999 took ${numberMs}ms (limit 200ms)", numberMs < 200L)
                assertEquals(9998, repo.liveIndex(source.id, checkNotNull(numbered).id))
                val page = repo.livePage(source.id, "Haber", "", "", 80, 9920).first()
                assertEquals(80, page.size)
                assertEquals(numbered.id, page[78].id)
                assertNull(repo.liveChannel(10001, source.id))
                assertNull(repo.liveChannel(0, source.id))
                val order = repo.liveChannelOrder(source.id)
                assertSame(order, repo.liveChannelOrder(source.id))
                assertEquals(10000, kotlinx.coroutines.withTimeout(10_000) { order.first { it.size == 10000 } }.size)
                // New queries obey the hidden list in both the selected row and COUNT prefix.
                repo.library.save(listOf(numbered.copy(category = "For Adults")))
                prepareTestCatalog(repo, source.id)
                assertNull(repo.library.liveIndex(source.id, numbered.id, hidden = listOf("For Adults")))
                assertEquals(9999, kotlinx.coroutines.withTimeout(10_000) { order.first { it.size == 9999 } }.size)
                assertEquals(9999, repo.library.liveCount(source.id, "", "", "", listOf("For Adults")).first())
                assertTrue(repo.library.livePage(source.id, "", "", "", 80, 9920, listOf("For Adults"))
                    .first().none { it.id == numbered.id })
                assertEquals(orderedTitles.last(), repo.library.liveAtOffset(source.id, 9998, listOf("For Adults"))?.title)
                Log.i("LumenaraBenchmark", "rows=10000 number=9999 queryMs=$numberMs pageRows=${page.size}")
                Log.i("LumenaraBenchmark", "rows=10000 importMs=$importMs queryMs=$queryMs")
            } finally {
                prefs.setShowAdult(previousAdult)
                repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
            }
        }
        Unit
    }
}
