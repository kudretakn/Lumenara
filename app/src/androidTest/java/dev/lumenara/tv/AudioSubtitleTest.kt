package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.lumenara.data.*
import dev.lumenara.catalog.displayTitle
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
        // Keep remote activation in the window containing this verified focused row.
        screen.onNode(matcher and isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        screen.waitForIdle()
    }
    private fun returnToDetails() {
        // Options and the startup timeline each consume Back. Observe each result before
        // issuing the next key, and fail if the real navigation contract does not complete.
        repeat(4) {
            if (screen.onAllNodesWithTag("details-content").fetchSemanticsNodes().isNotEmpty()) {
                screen.onNodeWithContentDescription("Tam ekran oynatıcı").assertDoesNotExist()
                return
            }
            screen.wp21AwaitNode(hasContentDescription("Tam ekran oynatıcı"))
            screen.back()
            screen.wp21AwaitWindowFrames()
        }
        screen.wp21AwaitNode(hasTestTag("details-content"))
        screen.onNodeWithContentDescription("Tam ekran oynatıcı").assertDoesNotExist()
    }
    private fun capture(name: String) {
        screen.wp21AwaitWindowFrames()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "screens/$name.png")
        output.parentFile!!.mkdirs()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(output))
    }
    private fun optionsScrollTo(matcher: SemanticsMatcher) {
        screen.wp21ScrollTo("player-options", matcher)
    }
    private fun assertEngineDelay(us: Long) {
        // Media3 reports the shift output's applied clock; VLC reads native spuDelay.
        waitFor(SemanticsMatcher.expectValue(SubtitleEngineDelayUs, us))
    }
    private fun assertOptionsKeepRemoteFocus() {
        screen.onNodeWithText("Devamını göster").assertDoesNotExist()
        val focusedOption = isFocused() and hasAnyAncestor(hasTestTag("player-options"))
        waitFor(focusedOption)
        screen.dpad(Key.DirectionLeft)
        screen.onNode(focusedOption).assertIsFocused()
    }

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
                        // VLC's seekable MP4 input reads with Range requests (206 required), like real providers.
                        val range = request.getHeader("Range")?.substringAfter("bytes=")
                        val start = range?.substringBefore("-")?.toIntOrNull() ?: 0
                        val end = (range?.substringAfter("-")?.toIntOrNull() ?: video.lastIndex)
                            .coerceAtMost(video.lastIndex)
                        if (start >= video.size || end < start) return MockResponse().setResponseCode(416)
                        return MockResponse().setResponseCode(if (range != null) 206 else 200)
                            .setHeader("Content-Type", "video/mp4")
                            .setHeader("Accept-Ranges", "bytes")
                            .apply { if (range != null) setHeader("Content-Range", "bytes $start-$end/${video.size}") }
                            .setBody(Buffer().write(video, start, end - start + 1))
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
                // Provider numeric IDs are hidden on cards, while stream identities stay intact.
                assertEquals(movieTitle.displayTitle(), movie.title)
                val versions = runBlocking { repo.versions(movie) }
                assertEquals(2, versions.size)
                val subtitledVersion = versions.single { it.title.contains("Altyazılı", ignoreCase = true) }
                val dubbedVersion = versions.single { it.title.contains("Dublaj", ignoreCase = true) }
                assertTrue(versions.all { it.title.startsWith(movieTitle) })
                runBlocking {
                    repo.updateMark(movie.id) { it.copy(favorite = true, listed = true, position = 500, duration = 120000, updated = 1) }
                    versions.forEach { assertTrue(repo.library.mark(it.id)!!.favorite); assertTrue(repo.library.mark(it.id)!!.listed) }
                }
                screen.openHubCard("Filmler")
                screen.openCategory("Tümü")
                val card = hasContentDescription(movieTitle.displayTitle()) and hasAnyAncestor(hasTestTag("poster-grid"))
                waitFor(card)
                screen.onAllNodes(card).assertCountEquals(1)
                select(card)
                screen.onNodeWithTag("details-content").performScrollToNode(hasTestTag("version-picker"))
                select(hasTestTag("version-picker"))
                waitFor(hasTestTag("version-menu"))
                waitFor(isFocused() and hasAnyAncestor(hasTestTag("version-menu")))
                capture("versions")
                select(hasTestTag("version-SUBTITLED"))
                screen.onNodeWithTag("details-content").performScrollToIndex(0)
                select(hasText("Devam et"))
                screen.waitUntil(30_000) { videoRequested.get() == "102.mp4" }
                screen.wp21AwaitMedia3Ready(runBlocking { repo.address(subtitledVersion) })
                waitFor(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                screen.dpad(Key.Menu)
                assertOptionsKeepRemoteFocus()
                optionsScrollTo(hasTestTag("version-DUBBED"))
                select(hasTestTag("version-DUBBED"))
                screen.waitUntil(30_000) { videoRequested.get() == "101.mp4" }
                screen.wp21AwaitMedia3Ready(runBlocking { repo.address(dubbedVersion) })
                assertEquals(0, searches.get()); assertEquals(0, downloads.get())
                waitFor(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                screen.dpad(Key.Menu)
                optionsScrollTo(hasTestTag("subtitle-search"))
                select(hasTestTag("subtitle-search"))
                waitFor(hasTestTag("subtitle-result-0"))
                screen.onAllNodesWithTag("subtitle-results").assertCountEquals(1)
                screen.onAllNodesWithTag("player-options").assertCountEquals(0)
                capture("subtitle-search")
                // Back restores the search row in the same options panel.
                screen.back()
                waitFor(hasTestTag("subtitle-search") and isFocused())
                select(hasTestTag("subtitle-search"))
                waitFor(hasTestTag("subtitle-result-0"))
                select(hasTestTag("subtitle-result-0"))
                waitFor(hasText("Türkçe altyazı eklendi"))
                val cue = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Altyazı · Türkçe altyazı sınaması")
                waitFor(cue)
                screen.dpad(Key.DirectionRight)
                waitFor(hasTestTag("vod-timeline"))
                screen.onNode(hasText("Kıyı") and hasAnyAncestor(hasTestTag("vod-timeline"))).assertExists()
                capture("subtitle-playing")
                assertEquals(2, searches.get()); assertEquals(1, downloads.get())
                val key = runBlocking { repo.preferenceKey(movie) }
                assertNotNull(repo.subtitles.cache.find(key))
                // Close and reopen: the same UTF-8 cache is attached with no subtitle request.
                returnToDetails()
                screen.onNodeWithTag("details-content").performScrollToIndex(0)
                select(hasText("Devam et"))
                waitFor(cue)
                screen.wp21AwaitMedia3Ready(runBlocking { repo.address(dubbedVersion) })
                assertEquals(2, searches.get()); assertEquals(1, downloads.get())
                waitFor(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                screen.dpad(Key.Menu)
                optionsScrollTo(hasTestTag("subtitle-delay-plus-100"))
                repeat(3) { select(hasTestTag("subtitle-delay-plus-100")) }
                waitFor(hasText("Altyazı gecikmesi · +0,3 sn"))
                assertEngineDelay(300_000L)
                val delayItem = dubbedVersion
                screen.waitUntil(5000) { runBlocking { prefs.subtitleDelay(delayItem) } == 300L }
                assertEquals(0L, runBlocking { prefs.subtitleDelay(versions.single { it.id != delayItem.id }) })
                // Back closes options first. The startup timeline can still be visible after
                // reopening cached playback and consumes its own Back before the player exits.
                screen.back()
                screen.wp21AwaitNode(hasTestTag("player-options"), present = false)
                screen.onNodeWithTag("player-options").assertDoesNotExist()
                returnToDetails()
                screen.onNodeWithTag("details-content").performScrollToIndex(0)
                select(hasText("Devam et"))
                waitFor(cue)
                screen.wp21AwaitMedia3Ready(runBlocking { repo.address(dubbedVersion) })
                assertEngineDelay(300_000L)
                waitFor(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                screen.dpad(Key.Menu)
                optionsScrollTo(hasText("Motor: Media3/VLC"))
                select(hasText("Motor: Media3/VLC"))
                waitFor(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "İndirilen altyazı etkin"))
                assertEngineDelay(300_000L)
                waitFor(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                screen.dpad(Key.Menu)
                assertOptionsKeepRemoteFocus()
                optionsScrollTo(hasTestTag("subtitle-delay-minus-1000"))
                select(hasTestTag("subtitle-delay-minus-1000"))
                waitFor(hasText("Altyazı gecikmesi · -0,7 sn"))
                assertEngineDelay(-700_000L)
                optionsScrollTo(hasTestTag("subtitle-delay-reset"))
                select(hasTestTag("subtitle-delay-reset"))
                waitFor(hasText("Altyazı gecikmesi · +0,0 sn"))
                assertEngineDelay(0L)
                screen.back()
                screen.dpad(Key.DirectionRight)
                waitFor(hasTestTag("vod-timeline"))
                capture("subtitle-playing-vlc")
                waitFor(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                screen.dpad(Key.Menu)
                optionsScrollTo(hasTestTag("subtitle-search"))
                select(hasTestTag("subtitle-search"))
                waitFor(hasTestTag("subtitle-result-0"))
                screen.onAllNodesWithTag("player-options").assertCountEquals(0)
                capture("subtitle-search-vlc")
                assertEquals(3, searches.get()); assertEquals(1, downloads.get())
                screen.wp21Await("version mark positions persisted equally") {
                    runBlocking {
                        val marks = versions.map { repo.library.mark(it.id) }
                        marks.all { it != null } && marks.map { it?.position }.distinct().size == 1
                    }
                }
                runBlocking {
                    val marks = versions.map { repo.library.mark(it.id)!! }
                    assertEquals(1, marks.map { it.position }.distinct().size)
                    assertEquals(1, marks.map { it.favorite }.distinct().size)
                }
            } finally {
                screen.runOnUiThread { screen.activity.setContent { } }
                screen.waitForIdle()
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
