package dev.lumenara.tv

import androidx.compose.ui.input.key.Key
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.lumenara.catalog.watched
import dev.lumenara.data.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SeriesJourneyTest {
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

    @Test fun m3uImportKeepsParentsProgressAndRemovesStaleEpisodesOnRefresh() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = "Test · M3U Diziler"
        MockWebServer().use { server ->
            server.start()
            val playlist = """
                #EXTM3U
                #EXTINF:-1 group-title="Diziler | Türk",Dizi Adı S01E02
                ${server.url("/series/u/p/123.mkv")}
                #EXTINF:-1 group-title="Diziler | Türk",Dizi Adı - 1. Sezon 1. Bölüm
                ${server.url("/series/u/p/122.mkv")}
                #EXTINF:-1 group-title="VOD | Aksiyon",Bir Film
                ${server.url("/vod/50.ts")}
            """.trimIndent()
            try {
                runBlocking {
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                    server.enqueue(MockResponse().setBody(playlist))
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                    val source = repo.library.snapshot().single { it.name == name }
                    val series = repo.library.browse(source.id, "SERIES", "", "", "", 10).first().single()
                    val episodes = repo.expand(series).seriesOrder() // M3U expansion reads the cache.
                    assertEquals(listOf(1, 2), episodes.map { it.seriesEpisode().episode })
                    assertTrue(episodes.all { it.parent == series.id })
                    assertEquals("Bir Film", repo.library.browse(source.id, "MOVIE", "", "", "", 10).first().single().title)
                    repo.updateMark(episodes.first().id) { it.copy(position = 90, duration = 100, updated = 1) }
                    repo.updateMark(episodes.last().id) { it.copy(position = 10, duration = 100, updated = 2, favorite = true, listed = true) }
                    assertEquals(listOf(series.id), repo.library.continueWatching(source.id).first().map { it.id })
                    assertEquals(episodes.last().id, episodes.continueItem(repo.library.episodeMarks(series.id).first())?.id)
                    listOf("", "favorite", "list").forEach { shelf ->
                        assertTrue(repo.library.browse(source.id, "", "", "", shelf, 20).first().none { it.kind == "EPISODE" })
                    }
                    assertEquals(listOf(series.id), repo.library.browse(source.id, "SERIES", "", "dizi", "", 10).first().map { it.id })
                    repo.updateMark(episodes.last().id) { it.copy(position = 90) }
                    assertTrue(repo.library.continueWatching(source.id).first().isEmpty())
                    repo.updateMark(episodes.last().id) { it.copy(position = 10) }
                    val staleAlias = "${source.id}:stale-episode-alias"
                    repo.library.redirect(ItemRedirect(staleAlias, episodes.first().id))
                    val refreshed = "#EXTM3U\n#EXTINF:-1 group-title=\"Diziler | Türk\",Dizi Adı S01E02\n${server.url("/series/u/p/123.mkv")}\n"
                    server.enqueue(MockResponse().setBody(refreshed))
                    repo.refresh(source.id)
                    assertEquals(series.id, repo.library.browse(source.id, "SERIES", "", "", "", 10).first().single().id)
                    assertEquals(listOf(episodes.last().id), repo.library.episodeSnapshot(series.id).map { it.id })
                    assertTrue(repo.library.mark(episodes.last().id)?.favorite == true)
                    assertTrue(repo.library.mark(episodes.last().id)?.listed == true)
                    assertEquals(10L, repo.library.mark(episodes.last().id)?.position)
                    assertEquals(listOf(series.id), repo.library.continueWatching(source.id).first().map { it.id })
                    assertNull(repo.library.mark(episodes.first().id))
                    assertNull(repo.library.item(episodes.first().id))
                    assertNull(repo.library.redirectedId(staleAlias))
                }
            } finally {
                runBlocking { repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) } }
            }
        }
    }

    @Test fun seriesSeasonEpisodePlaybackWatchedAndContinueAcrossSeasons() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val device = UiDevice.getInstance(test)
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val previousEngine = runBlocking { prefs.text("engine", "Media3").first() }
        val name = "Test · Dizi Yolculuğu"
        val requestedEpisode = AtomicReference("")
        fun capture(name: String) {
            screen.waitForIdle()
            screen.onNodeWithText("K", substring = false).assertDoesNotExist()
            val file = File(context.getExternalFilesDir(null), "screens/$name.png")
            checkNotNull(file.parentFile).mkdirs()
            assertTrue(device.takeScreenshot(file))
            // This provider deliberately has no artwork. The right safe margin must remain
            // plain neutral bg, never a bundled landscape presented as the show's backdrop.
            BitmapFactory.decodeFile(file.absolutePath).let { bitmap ->
                try {
                    assertEquals(Color.rgb(11, 11, 13), bitmap.getPixel(bitmap.width - 8, bitmap.height / 2))
                } finally { bitmap.recycle() }
            }
        }
        MockWebServer().use { server ->
            server.start()
            val video = test.context.assets.open("lumenara-test.mp4").use { it.readBytes() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = checkNotNull(request.requestUrl)
                    if (url.encodedPath.startsWith("/series/")) {
                        requestedEpisode.set(url.encodedPath.substringAfterLast('/'))
                        return MockResponse().setHeader("Content-Type", "video/mp4")
                            .setBody(Buffer().write(video))
                    }
                    return MockResponse().setBody(when (url.queryParameter("action")) {
                        null -> """{"user_info":{"auth":1}}"""
                        "get_series_categories" -> """[{"category_id":"3","category_name":"Diziler | Türk"}]"""
                        "get_series" -> """[{"series_id":10,"name":"Kıyı Yolculuğu","category_id":"3","plot":"İki sezonluk bir yolculuk."}]"""
                        "get_series_info" -> """{"episodes":{"2":[{"id":"202","episode_num":2,"title":"Dönüş","container_extension":"mp4"},{"id":"201","episode_num":1,"title":"Yeni Kıyı","container_extension":"mp4"}],"1":[{"id":"101","episode_num":1,"title":"Başlangıç","container_extension":"mp4"}]}}"""
                        else -> "[]"
                    })
                }
            }
            try {
                runBlocking {
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                    prefs.set("engine", "Media3")
                    repo.add(name, SourceSpec(server.url("/").toString(), true, "u", "p"))
                }
                val series = runBlocking {
                    val source = repo.library.snapshot().single { it.name == name }
                    repo.library.browse(source.id, "SERIES", "", "", "", 10).first().single()
                }
                screen.openHubCard("Diziler")
                screen.openCategory("Tümü")
                val card = hasContentDescription(series.title) and hasAnyAncestor(hasTestTag("poster-grid"))
                select(card)
                waitFor(hasTestTag("series-play") and isFocused())
                screen.onNodeWithTag("series-play").assertIsFocused()
                waitFor(hasTestTag("season-1"))
                waitFor(hasText("Oynat · 1. Bölüm"))
                screen.onNodeWithTag("series-play").assertIsFocused()
                device.pressDPadCenter()
                waitFor(hasContentDescription("Tam ekran oynatıcı"))
                screen.waitUntil(30_000) { requestedEpisode.get() == "101.mp4" }
                repeat(2) {
                    if (screen.onAllNodesWithContentDescription("Tam ekran oynatıcı")
                            .fetchSemanticsNodes().isNotEmpty()) screen.back()
                }
                waitFor(hasTestTag("series-play") and isFocused())
                screen.onNodeWithTag("series-play").assertIsFocused()
                capture("series")
                select(hasTestTag("season-1"))
                waitFor(hasTestTag("season-page"))
                val density = screen.onNodeWithTag("episode-list").fetchSemanticsNode().boundsInRoot.left / 48f
                val seasonBounds = screen.onNodeWithTag("season-1").fetchSemanticsNode().boundsInRoot
                assertEquals("Sezon çipi yüksekliği", 40f, seasonBounds.height / density, 1f)
                val episodes = runBlocking { repo.library.episodeSnapshot(series.id).seriesOrder() }
                assertEquals(listOf(1, 2, 2), episodes.map { it.seriesEpisode().season })
                val first = episodes.first()
                val second = episodes[1]
                val row = hasTestTag("episode-${first.id}")
                waitFor(row and isFocused())
                // Measure the 16:9 artwork slot, not the focused Surface clipped by its parent.
                val bounds = screen.onNodeWithTag("episode-artwork-${first.id}").fetchSemanticsNode().boundsInRoot
                assertEquals("Bölüm kartı genişliği", 180f, bounds.width / density, 1f)
                assertEquals("Bölüm görseli 16:9 kalmalı", 16f / 9f, bounds.width / bounds.height, .02f)
                capture("season")
                screen.ok()
                screen.waitUntil(60_000) {
                    runBlocking { repo.library.mark(first.id)?.let { watched(it.position, it.duration) } == true }
                }
                assertEquals("101.mp4", requestedEpisode.get())
                waitFor(hasText("S2 B1 · Yeni Kıyı")) // End card crosses the season boundary.
                screen.back()
                waitFor(row and isFocused() and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "İzlendi"))
                screen.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "İzlendi"))
                screen.onNodeWithText("1. Başlangıç").assertExists()
                // Both manual operations preserve the row's remote focus.
                screen.longPressOkNative()
                select(hasText("İzlenmedi olarak işaretle"))
                waitFor(row and isFocused() and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "İzlenmedi"))
                screen.onNode(row).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "İzlenmedi"))
                screen.longPressOkNative()
                select(hasText("İzlendi olarak işaretle"))
                waitFor(row and isFocused())
                screen.back()
                waitFor(hasTestTag("season-1") and isFocused())
                waitFor(hasTestTag("season-1") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "İzlendi"))
                // Back returns to the active season even if another control took focus.
                select(hasTestTag("season-2"))
                waitFor(hasTestTag("episode-${second.id}") and isFocused())
                screen.onNodeWithTag("series-play").performSemanticsAction(SemanticsActions.RequestFocus)
                waitFor(hasTestTag("series-play") and isFocused())
                screen.back()
                waitFor(hasTestTag("series-page"))
                waitFor(hasTestTag("season-2") and isFocused())
                // A partial episode in the next season must win over the completed season.
                runBlocking {
                    repo.updateMark(second.id) { it.copy(position = 300, duration = 10_000, updated = System.currentTimeMillis()) }
                }
                screen.onNodeWithTag("details-content").performScrollToIndex(0)
                select(hasText("Devam et · S2 B1"))
                screen.waitUntil(30_000) { requestedEpisode.get() == "201.mp4" }
                waitFor(hasContentDescription("Tam ekran oynatıcı"))
                // Back may first dismiss the timeline; stop as soon as the player has closed.
                repeat(2) {
                    if (screen.onAllNodesWithContentDescription("Tam ekran oynatıcı").fetchSemanticsNodes().isNotEmpty()) screen.back()
                }
                waitFor(hasTestTag("series-page"))
                waitFor(hasTestTag("series-play") and isFocused())
                screen.back()
                waitFor(card and isFocused())
                screen.returnToHub()
                screen.onNodeWithTag("hub").performScrollToNode(hasTestTag("shelf-Kaldığın yerden devam et"))
                val homeSeries = hasContentDescription(series.title) and hasAnyAncestor(hasTestTag("shelf-Kaldığın yerden devam et"))
                waitFor(homeSeries)
                screen.onNode(homeSeries).assertExists()
                val resumeLabel = hasText("S2 · B1", substring = true) or hasText("S2 · B2", substring = true)
                waitFor(resumeLabel and hasAnyAncestor(hasTestTag("shelf-Kaldığın yerden devam et")))
            } finally {
                runBlocking {
                    prefs.set("engine", previousEngine)
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                }
            }
        }
    }
}
