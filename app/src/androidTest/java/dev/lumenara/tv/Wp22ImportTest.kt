package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class Wp22ImportTest {
    @Test fun changedPlaylistPreservesUnchangedChannelNumbersAndReordersOnlyWhenNeeded(): Unit = runBlocking {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = "Test · WP22 channels ${System.nanoTime()}"
        MockWebServer().use { server ->
            server.start()
            fun playlist(movie: String, extra: Boolean = false) = "#EXTM3U\n" +
                "#EXTINF:-1 group-title=\"News\",Alpha\n${server.url("/alpha.ts")}\n" +
                "#EXTINF:-1 group-title=\"News\",Zulu\n${server.url("/zulu.ts")}\n" +
                "#EXTINF:-1 group-title=\"Movies\",$movie\n${server.url("/movie/one.mp4")}\n" +
                if (extra) "#EXTINF:-1 group-title=\"News\",Beta\n${server.url("/beta.ts")}\n" else ""
            try {
                server.enqueue(MockResponse().setBody(playlist("First")))
                repo.add(name, SourceSpec(server.url("/catalog").toString(), true))
                val source = repo.library.snapshot().single { it.name == name }
                suspend fun channels() = repo.library.browse(source.id, "LIVE", "", "", "", 20).first()
                    .associate { it.title to it.channelNumber }
                val before = channels()
                assertEquals(mapOf("Alpha" to 1, "Zulu" to 2), before)
                server.enqueue(MockResponse().setBody(playlist("Renamed")))
                repo.refresh(source.id)
                assertEquals(before, channels())
                server.enqueue(MockResponse().setBody(playlist("Renamed", true)))
                repo.refresh(source.id)
                assertEquals(mapOf("Alpha" to 1, "Beta" to 2, "Zulu" to 3), channels())
                val generation = repo.library.browse(source.id, "LIVE", "", "", "", 20).first().map { it.generation }
                server.enqueue(MockResponse().setBody(playlist("Renamed", true)))
                repo.refresh(source.id)
                assertEquals(generation, repo.library.browse(source.id, "LIVE", "", "", "", 20).first().map { it.generation })
                assertEquals(WP22_COMPUTATION_VERSION, repo.library.wp22State(source.id)?.version)
            } finally { repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) } }
        }
    }

    @Test fun streamingExpansionPreservesSortedDuplicateSelectionAndPreparedEpisodeFields(): Unit = runBlocking {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = "Test · WP22 episodes ${System.nanoTime()}"
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                    when (request.requestUrl?.queryParameter("action")) {
                        null -> """{"user_info":{"auth":1}}"""
                        "get_series" -> """[{"series_id":22,"name":"WP22 Show"}]"""
                        "get_series_info" -> """{"episodes":{"2":[{"id":1,"episode_num":2,"title":"Later","info":{"duration_secs":200}}],"1":[{"id":1,"episode_num":1,"title":"First","info":{"duration_secs":100}}]}}"""
                        else -> "[]"
                    })
            }
            server.start()
            try {
                repo.add(name, SourceSpec(server.url("/").toString(), true, "u", "p"))
                val source = repo.library.snapshot().single { it.name == name }
                val series = repo.library.browse(source.id, "SERIES", "", "", "", 10).first().single()
                val episode = repo.expand(series).single()
                assertEquals(1, episode.episodeSeason)
                assertEquals(1, episode.episodeNumber)
                assertEquals("First", episode.episodeTitle)
                assertEquals(1, episode.episodeVersion)
                assertEquals(100_000L, repo.library.mark(episode.id)?.duration)
            } finally { repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) } }
        }
    }
}
