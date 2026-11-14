package dev.lumenara.data

import dev.lumenara.catalog.SubtitleType
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SubtitleApiTest {
    private val cue = "1\n00:00:00,000 --> 00:00:10,000\nTürkçe altyazı\n"
    private fun endpoint(server: MockWebServer) = SubtitleEndpoint(server.url("/api/v1/").toString(), server.url("/").toString(), true)
    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun openSubtitlesLoginSearchAndDownloadUseRequiredHeadersAndMetadata() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"token":"test-token","unknown":true}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"attributes":{"language":"tr","release":"Film.1080p","download_count":123,"files":[{"file_id":91,"file_name":"film.srt"}]}}]}"""))
        server.enqueue(MockResponse().setBody("""{"link":"${server.url("/file.srt")}","remaining":4}"""))
        server.enqueue(MockResponse().setBody(cue))
        val api = OpenSubtitles(SubtitleCredentials("test-key", "user", "password"), endpoint(server))
        val rows = api.search(SubtitleQuery("Film", "2024", "123", season = 2, episode = 5)).entries
        assertEquals("Film.1080p", rows.single().release)
        assertEquals(123, rows.single().downloads)
        val downloaded = api.download(rows.single())
        assertEquals(cue, downloaded.first.toString(Charsets.UTF_8)); assertEquals(4, downloaded.second)
        val login = server.takeRequest()
        assertEquals("/api/v1/login", login.requestUrl!!.encodedPath)
        assertEquals("test-key", login.getHeader("Api-Key")); assertEquals("Lumenara v0.4", login.getHeader("User-Agent"))
        val search = server.takeRequest()
        assertEquals("Bearer test-token", search.getHeader("Authorization"))
        assertEquals("123", search.requestUrl!!.queryParameter("tmdb_id"))
        assertEquals("2", search.requestUrl!!.queryParameter("season_number"))
        assertEquals("5", search.requestUrl!!.queryParameter("episode_number"))
        assertEquals("tr", search.requestUrl!!.queryParameter("languages"))
        assertEquals("{\"file_id\":91}", server.takeRequest().body.readUtf8())
        val file = server.takeRequest()
        assertNull(file.getHeader("Authorization")); assertNull(file.getHeader("Api-Key"))
    }
    @Test fun queryFallsBackToTitleYearAndImdbIsNormalized() = MockWebServer().use { server ->
        repeat(2) { server.enqueue(MockResponse().setBody("{\"data\":[]}")) }
        val api = OpenSubtitles(SubtitleCredentials("key"), endpoint(server))
        api.search(SubtitleQuery("Kıyı", "2024"))
        val title = server.takeRequest().requestUrl!!
        assertEquals("Kıyı", title.queryParameter("query")); assertEquals("2024", title.queryParameter("year"))
        api.search(SubtitleQuery("Kıyı", imdb = "tt1234"))
        assertEquals("1234", server.takeRequest().requestUrl!!.queryParameter("imdb_id"))
    }
    @Test fun emptyAndQuotaResponsesFallBackToSubdlButAuthenticationErrorsDoNot() = runBlocking {
        for (code in listOf(200, 406, 429, 401, 500)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("{\"data\":[]}"))
            server.enqueue(MockResponse().setBody("""{"status":true,"subtitles":[{"url":"/subtitle/a.zip","language":"Turkish","release_name":"Film","downloads":3,"new_field":{}}]}"""))
            val dir = Files.createTempDirectory("subtitle-test").toFile()
            try {
                val service = SubtitleService({ SubtitleCredentials("open", subdlKey = "subdl") }, SubtitleCache(dir))
                service.openEndpoint = endpoint(server); service.subdlEndpoint = endpoint(server)
                val response = runCatching { service.search(SubtitleQuery("Film")) }
                if (code in listOf(401, 500)) {
                    assertTrue(response.exceptionOrNull() is SubtitleFailure); assertEquals(1, server.requestCount)
                } else {
                    val result = response.getOrThrow()
                    assertEquals("Subdl", result.entries.single().source)
                    if (code != 200) assertTrue(result.message.contains("günlük sınırı"))
                    server.takeRequest()
                    val subdl = server.takeRequest().requestUrl!!
                    assertEquals("subdl", subdl.queryParameter("api_key")); assertEquals("TR", subdl.queryParameter("languages"))
                }
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun subdlHandlesNestedResultsArchivesAndUtf8Cache() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"data":{"subtitles":[{"download_url":"a.zip","name":"Release","language":"TR"}]},"future":123}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(zip("readme.txt" to "ignore", "folder/cue.vtt" to "WEBVTT\n\n00:00.000 --> 00:10.000\nTürkçe"))))
        val api = Subdl("key", endpoint(server))
        val rows = api.search(SubtitleQuery("Film", tmdb = "10", season = 1, episode = 2)).entries
        assertEquals("Release", rows.single().release)
        val file = api.download(rows.single())
        assertEquals("cue.vtt", file.second)
        val dir = Files.createTempDirectory("subtitle-cache").toFile()
        try {
            val cache = SubtitleCache(dir)
            val saved = cache.save("production|1|2", file.first, file.second)
            assertEquals(SubtitleType.VTT.extension, saved.extension)
            assertEquals(saved, cache.find("production|1|2")); assertNull(cache.find("production|1|3"))
            assertTrue(saved.readText().contains("Türkçe"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun malformedResponsesAndQuotaErrorsAreSafe() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("invalid json password secret"))
        val e = runCatching { OpenSubtitles(SubtitleCredentials("secret"), endpoint(server)).search(SubtitleQuery("Film")) }.exceptionOrNull()
        assertTrue(e is SubtitleFailure); assertFalse(e!!.message.orEmpty().contains("secret"))
        server.enqueue(MockResponse().setBody("""{"status":false,"message":"daily quota exceeded"}"""))
        val subdl = runCatching { Subdl("secret", endpoint(server)).search(SubtitleQuery("Film")) }.exceptionOrNull()
        assertTrue((subdl as SubtitleFailure).quota)
    }
    @Test fun missingKeysDoNotIssueRequestsAndEmptySearchDoesNotDownload() = runBlocking {
        val dir = Files.createTempDirectory("subtitle-cache").toFile()
        try {
            val service = SubtitleService({ SubtitleCredentials() }, SubtitleCache(dir))
            assertFalse(service.configured())
            assertTrue(service.search(SubtitleQuery("Film")).message.contains("Ayarlar"))
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun archiveWithoutSubtitlesIsRejectedAndNoZipPathIsWritten() {
        val error = runCatching { Subdl.unzip(zip("../../secret.txt" to "none")) }.exceptionOrNull()
        assertTrue(error is SubtitleFailure)
        val (bytes, name) = Subdl.unzip(zip("../../valid.srt" to cue))
        assertEquals("valid.srt", name); assertEquals(cue, bytes.toString(Charsets.UTF_8))
    }
    @Test fun cacheNormalizesWindows1254AndReplacesThePreviousFormat() {
        val dir = Files.createTempDirectory("subtitle-cache").toFile()
        try {
            val cache = SubtitleCache(dir)
            val bytes = cue.toByteArray(java.nio.charset.Charset.forName("windows-1254"))
            val srt = cache.save("film", bytes, "film.srt")
            assertArrayEquals(cue.toByteArray(Charsets.UTF_8), srt.readBytes())
            val vtt = cache.save("film", "WEBVTT\n\n00:00.000 --> 00:10.000\nTürkçe".toByteArray(), "film.vtt")
            assertFalse(srt.exists()); assertEquals(vtt, cache.find("film"))
            assertEquals(1, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun xtreamInfoProvidesTmdbImdbAndYearWithoutSearchingTmdb() = MockWebServer().use { server ->
        val provider = Xtream(SourceSpec(server.url("/").toString(), true, "u", "p"))
        server.enqueue(MockResponse().setBody("""{"info":{"tmdb_id":73,"imdb_id":"1234","releasedate":"2024-01-01"}}"""))
        val movie = provider.subtitleInfo(server.url("/movie/u/p/101.mp4").toString(), "MOVIE", "Kıyı", "")
        assertEquals("73", movie.tmdb); assertEquals("tt1234", movie.imdb); assertEquals("2024", movie.year)
        val request = server.takeRequest().requestUrl!!
        assertEquals("get_vod_info", request.queryParameter("action")); assertEquals("101", request.queryParameter("vod_id"))
        server.enqueue(MockResponse().setBody("""{"info":{"tmdb":"321"},"episodes":{}}"""))
        val series = provider.subtitleInfo(server.url("/player_api.php?action=get_series_info&series_id=10").toString(), "SERIES", "Dizi", "2023")
        assertEquals("321", series.tmdb)
        assertEquals("get_series_info", server.takeRequest().requestUrl!!.queryParameter("action"))
    }
    @Test fun downloadQuotaAndUnsafeRedirectAreRejectedWithoutLeakingCredentials() = MockWebServer().use { server ->
        val api = OpenSubtitles(SubtitleCredentials("secret"), endpoint(server))
        val result = SubtitleResult("1", "tr", "Film", 0, "OpenSubtitles", "film.srt")
        server.enqueue(MockResponse().setResponseCode(406))
        assertTrue((runCatching { api.download(result) }.exceptionOrNull() as SubtitleFailure).quota)
        server.enqueue(MockResponse().setBody("""{"link":"http://remote.invalid/file.srt"}"""))
        val error = runCatching { api.download(result) }.exceptionOrNull()
        assertNotNull(error); assertFalse(error!!.message.orEmpty().contains("secret"))
        // No request is issued to the disallowed file location.
        assertEquals(2, server.requestCount)
    }
    @Test fun releaseEndpointsRejectHttpAndRemoteTestHttp() {
        assertTrue(runCatching { SubtitleEndpoint("http://example.com/") }.isFailure)
        assertTrue(runCatching { SubtitleEndpoint("http://example.com/", testHttp = true) }.isFailure)
    }
}
