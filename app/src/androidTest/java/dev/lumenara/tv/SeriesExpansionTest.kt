package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SeriesExpansionTest {
    @Test fun reopeningAnXtreamSeriesUsesCachedEpisodesAndPreservesMarks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = Repository.get(context)
        val name = "Test · P4 expansion ${System.nanoTime()}"
        val requests = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when (request.requestUrl?.queryParameter("action")) {
                        null -> """{"user_info":{"auth":1}}"""
                        "get_series_categories" -> """[{"category_id":"3","category_name":"Diziler"}]"""
                        "get_series" -> """[{"series_id":10,"name":"P4 Dizi Dublaj","category_id":"3"},{"series_id":11,"name":"P4 Dizi Altyazılı","category_id":"3"}]"""
                        "get_series_info" -> {
                            requests.incrementAndGet()
                            val id = if (request.requestUrl?.queryParameter("series_id") == "10") 100 else 110
                            """{"episodes":{"1":[{"id":"${id + 1}","episode_num":1,"title":"İlk","container_extension":"mp4","info":{"duration_secs":100}},{"id":"${id + 2}","episode_num":2,"title":"Son","container_extension":"mp4","info":{"duration_secs":200}}]}}"""
                        }
                        else -> "[]"
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            try {
                repo.add(name, SourceSpec(server.url("/").toString(), true, "u", "p"))
                val source = repo.library.snapshot().single { it.name == name }
                val series = repo.library.browse(source.id, "SERIES", "", "", "", 10).first().single()
                val episodes = repo.expand(series).seriesOrder()
                assertEquals(2, episodes.size)
                assertEquals(100_000L, repo.library.mark(episodes.first().id)?.duration)
                val progress = Mark(episodes.first().id, favorite = true, position = 500, duration = 100_000, updated = 5)
                repo.library.save(progress)
                coroutineScope {
                    val a = async { repo.expand(series).seriesOrder() }
                    val b = async { repo.expand(series).seriesOrder() }
                    assertEquals(episodes.map { it.id }, a.await().map { it.id })
                    assertEquals(episodes.map { it.id }, b.await().map { it.id })
                }
                assertEquals(1, requests.get())
                assertEquals(progress, repo.library.mark(episodes.first().id))
                assertTrue(Preferences(context).text("series_expanded_${series.id}").first().toLong() > 0)
                // Batched sibling marks must still transfer progress by season/episode number.
                val sibling = repo.library.versionRows(series.versionKey).single { it.id != series.id }
                val alternate = repo.expand(sibling).seriesOrder()
                assertEquals(progress.copy(id = alternate.first().id), repo.library.mark(alternate.first().id))
                repo.expand(sibling)
                assertEquals(2, requests.get())
            } finally {
                repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
            }
        }
    }
}
