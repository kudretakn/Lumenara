package dev.lumenara.tv

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Item
import dev.lumenara.data.Repository
import dev.lumenara.data.SourceSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeCatalogTest {
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
                    appendLine("#EXTINF:-1 group-title=\"Film\" tvg-logo=\"https://example.invalid/poster/$id.jpg\",Film ${id / 3}$suffix")
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
                Log.i("LumenaraBenchmark", "rows=10000 importMs=$importMs queryMs=$queryMs")
            } finally {
                repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
            }
        }
        Unit
    }
}
