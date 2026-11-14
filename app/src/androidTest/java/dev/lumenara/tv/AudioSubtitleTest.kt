package dev.lumenara.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.lumenara.data.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioSubtitleTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private fun waitFor(matcher: SemanticsMatcher) {
        screen.waitUntil(30_000) { screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun select(matcher: SemanticsMatcher) {
        waitFor(matcher)
        screen.onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus)
        waitFor(matcher and isFocused())
        screen.ok()
    }
    private fun capture(name: String) {
        screen.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "screens/$name.png")
        output.parentFile!!.mkdirs()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(output))
    }
    private fun optionsScrollTo(matcher: SemanticsMatcher) {
        screen.onNodeWithTag("player-options").performScrollToNode(matcher)
    }
    private fun navigation(label: String) = hasContentDescription(label) and
        hasAnyAncestor(hasTestTag("nav-open") or hasTestTag("nav-closed"))

    @Test fun xtreamVersionsShareStateAndSubtitleSearchAttachesAndReusesCache() {
        val test = InstrumentationRegistry.getInstrumentation()
        val repo = Repository.get(test.targetContext)
        val prefs = Preferences(test.targetContext)
        val previousRemaining = runBlocking { prefs.text("subtitle_remaining").first() }
        val previousEngine = runBlocking { prefs.text("engine", "Media3").first() }
        val previousKeys = runBlocking { listOf("opensubtitles_key", "opensubtitles_user", "opensubtitles_password", "subdl_key")
            .associateWith { prefs.secret(it) } }
        val previousOpen = repo.subtitles.openEndpoint
        val previousSubdl = repo.subtitles.subdlEndpoint
        val sourceName = "Test · Ses ve Altyazı"
        val movieTitle = "Kıyı ${System.nanoTime()}"
        val videoRequested = AtomicReference("")
        val searches = AtomicInteger()
        val downloads = AtomicInteger()
        MockWebServer().use { server ->
            server.start()
            val video = test.context.assets.open("audio-subtitle-test.mp4").use { it.readBytes() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = checkNotNull(request.requestUrl)
                    if (url.encodedPath.startsWith("/movie/")) {
                        videoRequested.set(url.encodedPath.substringAfterLast('/'))
                        // VLC's seekable MP4 input reads with Range requests, like real providers allow.
                        val start = request.getHeader("Range")?.substringAfter("bytes=")
                            ?.substringBefore("-")?.toIntOrNull() ?: 0
                        if (start >= video.size) return MockResponse().setResponseCode(416)
                        return MockResponse().setResponseCode(if (start > 0) 206 else 200)
                            .setHeader("Content-Type", "video/mp4")
                            .setHeader("Accept-Ranges", "bytes")
                            .setHeader("Content-Range", "bytes $start-${video.lastIndex}/${video.size}")
                            .setBody(Buffer().write(video, start, video.size - start))
                    }
                    if (url.encodedPath == "/api/v1/subtitles") {
                        searches.incrementAndGet()
                        assertEquals("73", url.queryParameter("tmdb_id"))
                        assertEquals("fixture-key", request.getHeader("Api-Key"))
                        return MockResponse().setBody("""{"data":[{"attributes":{"language":"tr","release":"Kıyı.1080p","download_count":42,"files":[{"file_id":9,"file_name":"kiyi.srt"}]}}]}""")
                    }
                    if (url.encodedPath == "/api/v1/download") {
                        downloads.incrementAndGet()
                        return MockResponse().setBody("""{"link":"${server.url("/kiyi.srt")}","remaining":8}""")
                    }
                    if (url.encodedPath == "/kiyi.srt") return MockResponse().setBody(
                        "1\n00:00:00,000 --> 00:02:00,000\nTürkçe altyazı sınaması\n")
                    return MockResponse().setBody(when (url.queryParameter("action")) {
                        null -> """{"user_info":{"auth":1}}"""
                        "get_vod_categories" -> """[{"category_id":"1","category_name":"Filmler"}]"""
                        "get_vod_streams" -> """[{"stream_id":101,"name":"$movieTitle (Dublaj)","category_id":"1","container_extension":"mp4"},{"stream_id":102,"name":"$movieTitle (Altyazılı)","category_id":"1","container_extension":"mp4"}]"""
                        "get_vod_info" -> """{"info":{"tmdb_id":"73","imdb_id":"tt0073"}}"""
                        else -> "[]"
                    })
                }
            }
            try {
                val cards = runBlocking {
                    prefs.set("engine", "Media3")
                    prefs.setSecret("opensubtitles_key", "fixture-key")
                    assertFalse(prefs.text("secret_opensubtitles_key").first().contains("fixture-key"))
                    prefs.setSecret("opensubtitles_user", ""); prefs.setSecret("opensubtitles_password", ""); prefs.setSecret("subdl_key", "")
                    repo.subtitles.openEndpoint = SubtitleEndpoint(server.url("/api/v1/").toString(), testHttp = true)
                    repo.subtitles.subdlEndpoint = SubtitleEndpoint(server.url("/api/v1/").toString(), testHttp = true)
                    repo.library.snapshot().filter { it.name == sourceName }.forEach { repo.remove(it.id) }
                    repo.add(sourceName, SourceSpec(server.url("/").toString(), true, "u", "p"))
                    val source = repo.library.snapshot().single { it.name == sourceName }
                    repo.library.browse(source.id, "MOVIE", "", "", "", 20).first()
                }
                assertEquals(1, cards.size)
                val movie = cards.single()
                assertEquals(movieTitle, movie.title)
                val versions = runBlocking { repo.versions(movie) }
                assertEquals(2, versions.size)
                runBlocking {
                    repo.updateMark(movie.id) { it.copy(favorite = true, listed = true, position = 500, duration = 120000, updated = 1) }
                    versions.forEach { assertTrue(repo.library.mark(it.id)!!.favorite); assertTrue(repo.library.mark(it.id)!!.listed) }
                }
                select(navigation("Filmler"))
                val card = hasContentDescription(movieTitle) and hasAnyAncestor(hasTestTag("poster-grid"))
                waitFor(card)
                screen.onAllNodes(card).assertCountEquals(1)
                select(card)
                screen.onNodeWithTag("details-content").performScrollToNode(hasTestTag("version-SUBTITLED"))
                select(hasTestTag("version-SUBTITLED"))
                capture("versions")
                screen.onNodeWithTag("details-content").performScrollToIndex(0)
                select(hasText("Devam et"))
                screen.waitUntil(30_000) { videoRequested.get() == "102.mp4" }
                screen.dpad(Key.Menu)
                optionsScrollTo(hasTestTag("version-DUBBED"))
                select(hasTestTag("version-DUBBED"))
                screen.waitUntil(30_000) { videoRequested.get() == "101.mp4" }
                assertEquals(0, searches.get()); assertEquals(0, downloads.get())
                screen.dpad(Key.Menu)
                optionsScrollTo(hasTestTag("subtitle-search"))
                select(hasTestTag("subtitle-search"))
                waitFor(hasTestTag("subtitle-result-0"))
                capture("subtitle-search")
                select(hasTestTag("subtitle-result-0"))
                waitFor(hasText("Altyazı eklendi · OpenSubtitles kalan indirme: 8"))
                val cue = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Altyazı · Türkçe altyazı sınaması")
                waitFor(cue)
                capture("subtitle-playing")
                assertEquals(1, searches.get()); assertEquals(1, downloads.get())
                val key = runBlocking { repo.preferenceKey(movie) }
                assertNotNull(repo.subtitles.cache.find(key))
                // Close and reopen: the same UTF-8 cache is attached with no subtitle request.
                repeat(2) {
                    if (screen.onAllNodesWithContentDescription("Tam ekran oynatıcı").fetchSemanticsNodes().isNotEmpty()) screen.back()
                }
                waitFor(hasTestTag("details-content"))
                screen.onNodeWithTag("details-content").performScrollToIndex(0)
                select(hasText("Devam et"))
                waitFor(cue)
                assertEquals(1, searches.get()); assertEquals(1, downloads.get())
                screen.dpad(Key.Menu)
                optionsScrollTo(hasText("+0,5 sn"))
                select(hasText("+0,5 sn"))
                waitFor(hasText("Altyazı kaydırma · 0.5 sn"))
                screen.waitUntil(5000) { runBlocking { prefs.text("delay_$key").first() } == "500" }
                optionsScrollTo(hasText("VLC ile aç"))
                select(hasText("VLC ile aç"))
                waitFor(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "İndirilen altyazı etkin"))
                screen.dpad(Key.Menu)
                optionsScrollTo(hasText("Altyazı gecikmesi · 500 ms"))
                waitFor(hasText("Altyazı gecikmesi · 500 ms"))
                select(hasText("−0,5 sn"))
                waitFor(hasText("Altyazı gecikmesi · 0 ms"))
                assertEquals(1, searches.get()); assertEquals(1, downloads.get())
                runBlocking {
                    val marks = versions.map { repo.library.mark(it.id)!! }
                    assertEquals(1, marks.map { it.position }.distinct().size)
                    assertEquals(1, marks.map { it.favorite }.distinct().size)
                }
            } finally {
                runBlocking {
                    prefs.set("engine", previousEngine)
                    prefs.set("subtitle_remaining", previousRemaining)
                    previousKeys.forEach { (key, value) -> prefs.setSecret(key, value) }
                    repo.subtitles.openEndpoint = previousOpen
                    repo.subtitles.subdlEndpoint = previousSubdl
                    repo.library.snapshot().filter { it.name == sourceName }.forEach { repo.remove(it.id) }
                }
            }
        }
    }
}
