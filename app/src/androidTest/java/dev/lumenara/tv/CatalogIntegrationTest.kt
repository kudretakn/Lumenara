package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

class CatalogIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = Repository.get(context)
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun cleanup() {
        runBlocking {
            repo.library
                .snapshot()
                .filter { it.name.startsWith("Test · ") }
                .forEach { repo.remove(it.id) }
        }
        server.shutdown()
    }

    @Test
    fun refreshRollbackMarksEncryptionAndEpg() = runBlocking {
        val url = server.url("/stream?token=fixture").toString()
        val list = "#EXTM3U\n#EXTINF:-1 tvg-id=\"tr7\" group-title=\"Spor\",Işık\n$url"
        fun response(data: String) = server.enqueue(MockResponse().setBody(data))
        response(list)
        repo.add("Test · Ana", SourceSpec(server.url("/catalog").toString(), true))
        val source = repo.library.snapshot().single { it.name == "Test · Ana" }
        val item = repo.library.browse(source.id, "LIVE", "", "isik", "", 20).first().single()
        assertFalse(item.encryptedUrl.contains("fixture"))
        assertFalse(source.endpoint.contains("catalog"))
        assertEquals(url, repo.address(item))
        repo.updateMark(item.id) { it.copy(favorite = true, position = 500) }
        response(list)
        repo.refresh(source.id)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { repo.refresh(source.id) }.isFailure)
        assertEquals(
            item.id,
            repo.library.browse(source.id, "", "", "", "favorite", 20).first().single().id,
        )
        assertEquals(500L, repo.library.mark(item.id)?.position)
        response(
            """<tv><programme channel="tr7" start="20261105120000 +0300" stop="20261105140000 +0300"><title>Öğle</title></programme></tv>"""
        )
        repo.addGuide(source.id, server.url("/guide").toString())
        val slot =
            repo.guide.guide(source.id, "tr7", "isik", 0, Long.MAX_VALUE, "").first().single()
        assertEquals("Öğle", slot.title)
        response(list)
        repo.add("Test · İkinci", SourceSpec(server.url("/second").toString(), true))
        val other = repo.library.snapshot().single { it.name == "Test · İkinci" }
        assertNotEquals(
            item.id,
            repo.library.browse(other.id, "", "", "", "", 20).first().single().id,
        )
        repo.remove(source.id)
        assertNull(repo.library.mark(item.id))
        assertEquals(1, repo.library.count(other.id))
    }

    @Test
    fun xtreamApiCatalogAndEpisodes() = runBlocking {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body =
                        when (request.requestUrl?.queryParameter("action")) {
                            null -> """{"user_info":{"auth":1}}"""
                            "get_live_categories",
                            "get_vod_categories",
                            "get_series_categories" ->
                                """[{"category_id":3,"category_name":"Kültür"}]"""
                            "get_live_streams" ->
                                """[{"stream_id":1,"name":"Bir","category_id":"3"}]"""
                            "get_vod_streams" ->
                                """[{"stream_id":2,"name":"Film","category_id":"3"}]"""
                            "get_series" -> """[{"series_id":5,"name":"Dizi","category_id":"3"}]"""
                            "get_series_info" ->
                                """{"episodes":{"1":[{"id":"7","episode_num":1,"title":"Pilot","container_extension":"mp4"}]}}"""
                            else -> return MockResponse().setResponseCode(404)
                        }
                    return MockResponse().setBody(body)
                }
            }
        repo.add("Test · API", SourceSpec(server.url("/iptv/").toString(), true, "fixture", "abc"))
        val source = repo.library.snapshot().single { it.name == "Test · API" }
        assertEquals(3, source.count)
        val series = repo.library.browse(source.id, "SERIES", "", "", "", 10).first().single()
        val episode = repo.expand(series).single()
        assertTrue(repo.address(episode).endsWith("/iptv/series/fixture/abc/7.mp4"))
    }

    @Test
    fun refusesHttpWithoutPerSourceOptIn() = runBlocking {
        assertTrue(
            runCatching { repo.add("Test · Kapalı", SourceSpec(server.url("/").toString(), false)) }
                .isFailure
        )
        assertEquals(0, server.requestCount)
        assertTrue(repo.library.snapshot().none { it.name == "Test · Kapalı" })
    }
}
