package dev.streamora.tv

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.SuccessResult
import dev.streamora.data.Feed
import dev.streamora.data.Mapping
import dev.streamora.data.Slot
import dev.streamora.data.Preferences
import dev.streamora.data.Repository
import dev.streamora.data.SourceSpec
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Original fictional artwork and metadata live only in androidTest, never in the user's library.
 */
class CinemaJourneyTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test
    fun focusedArtworkDetailsFiltersAndWatchlist() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val repo = Repository.get(context)
        val device = UiDevice.getInstance(test)
        val titles =
            listOf(
                "Açık Deniz",
                "Kızıl Ufuk",
                "Kumların Ötesi",
                "Gece Hattı",
                "Mavi Sessizlik",
                "Son Sefer",
                "Kadife Gece",
            )
        val art = listOf("coast", "tile0", "tile1", "tile2", "tile3", "tile4", "tile5")
        val previous = Coil.imageLoader(context)
        val loader =
            ImageLoader.Builder(context)
                .components {
                    add(
                        Interceptor { chain ->
                            val address = chain.request.data.toString()
                            if (!address.startsWith("https://cinema.invalid/"))
                                chain.proceed(chain.request)
                            else {
                                val file = address.substringAfterLast('/')
                                val bitmap =
                                    test.context.assets.open("cinema/$file").use {
                                        BitmapFactory.decodeStream(it)
                                    }
                                SuccessResult(
                                    BitmapDrawable(context.resources, bitmap),
                                    chain.request,
                                    DataSource.MEMORY,
                                )
                            }
                        }
                    )
                }
                .build()
        Coil.setImageLoader(loader)
        fun node(text: String): SemanticsNodeInteraction {
            try {
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("Beklenen: $text\n" + screen.onRoot().printToString(), e)
            }
            return screen.onAllNodesWithText(text).onFirst()
        }
        fun focus(node: SemanticsNodeInteraction) {
            node.performSemanticsAction(SemanticsActions.RequestFocus)
            screen.waitUntil(30_000) { isFocused().matches(node.fetchSemanticsNode()) }
        }
        fun describedIn(description: String, tag: String) =
            hasContentDescription(description) and hasAnyAncestor(hasTestTag(tag))
        fun navigation(name: String) = hasContentDescription(name) and
            hasAnyAncestor(hasTestTag("nav-open") or hasTestTag("nav-closed"))
        fun section(name: String) {
            focus(screen.onNode(navigation(name)))
            screen.ok()
            screen.waitUntil(30_000) {
                screen.onAllNodesWithTag("section-$name").fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun waitFor(matcher: SemanticsMatcher) {
            try {
                screen.waitUntil(30_000) {
                    screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("Beklenen: $matcher\n" + screen.onRoot().printToString(), e)
            }
        }
        fun searchShelf(tag: String) {
            screen.waitUntil(30_000) {
                runCatching {
                    screen.onNodeWithTag("search-results").performScrollToNode(hasTestTag(tag))
                    screen.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
        }
        fun waitAbsent(matcher: SemanticsMatcher) {
            screen.waitUntil(30_000) { screen.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
        }
        fun select(text: String) {
            focus(node(text))
            screen.ok()
        }
        fun focusFilters() {
            val button = isFocusable() and hasText("Filtrele")
            waitFor(button)
            focus(screen.onNode(button))
        }
        fun capture(name: String) {
            // A real device screenshot includes GPU-composited image transitions and TV focus
            // rings.
            val readyAt = android.os.SystemClock.uptimeMillis() + 1300
            screen.waitUntil(30_000) { android.os.SystemClock.uptimeMillis() >= readyAt }
            val file = File(context.getExternalFilesDir(null), "$name.png")
            checkNotNull(file.parentFile).mkdirs()
            assertTrue("Ekran görüntüsü kaydedilmeli: $name", device.takeScreenshot(file))
            device.executeShellCommand("mkdir -p /sdcard/Download/streamora-ui")
            device.executeShellCommand(
                "cp ${context.getExternalFilesDir(null)}/$name.png /sdcard/Download/streamora-ui/${file.name}"
            )
        }
        MockWebServer().use { server ->
            server.start()
            val video = test.context.assets.open("streamora-test.mp4").use { it.readBytes() }
            val preferences = Preferences(context)
            val previousRecent = runBlocking { preferences.text("recent_live").first() }
            val previousEngine = runBlocking { preferences.text("engine", "Media3").first() }
            runBlocking {
                repeat(25) { preferences.recordLive("fixture-$it") }
                preferences.recordLive("fixture-10")
                val ids = preferences.recentLive.first()
                assertEquals(20, ids.size)
                assertEquals("fixture-10", ids.first())
                assertEquals(20, ids.distinct().size)
                preferences.set("recent_live", "")
                preferences.set("engine", "Media3")
            }
            val catalog = JSONArray()
            titles.forEachIndexed { i, title ->
                catalog.put(
                    JSONObject()
                        .put("stream_id", i + 1)
                        .put("name", title)
                        .put("category_id", "1")
                        .put("stream_icon", "https://cinema.invalid/${art[i]}.jpg")
                        .put(
                            "backdrop_path",
                            if (i == 0) JSONArray().put("https://cinema.invalid/${art[i]}.jpg")
                            else JSONArray(),
                        )
                        .put("year", "2025")
                        .put("genre", if (i == 0) "Gizem · Dram" else "Macera")
                        .put("rating", "8.2")
                        .put("cast", "Deniz Aral, Ece Yalın, Kerem Sönmez")
                        .put(
                            "plot",
                            if (i == 0)
                                "Unutulmuş bir deniz feneri. Yıllar sonra gelen bir mektup. Ege’nin uzak kıyılarında, geçmişin sessizliği yerini beklenmedik bir yolculuğa bırakıyor."
                            else "Sınırların ötesinde, her yolculuk yeni bir hikâye başlatır.",
                        )
                )
            }
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.requestUrl?.encodedPath?.startsWith("/live/") == true)
                            return MockResponse().setHeader("Content-Type", "video/mp4")
                                .setBody(Buffer().write(video))
                        return MockResponse().setBody(
                            when (request.requestUrl?.queryParameter("action")) {
                                null -> """{"user_info":{"auth":1}}"""
                                "get_vod_categories" ->
                                    """[{"category_id":1,"category_name":"Sinema"}]"""
                                "get_vod_streams" -> catalog.toString()
                                "get_live_categories" ->
                                    """[{"category_id":2,"category_name":"Haber"}]"""
                                "get_live_streams" ->
                                    """[{"stream_id":101,"name":"Test Haber","category_id":"2","stream_icon":"https://cinema.invalid/tile0.jpg"}]"""
                                else -> "[]"
                            }
                        )
                    }
                }
            try {
                node("✓ M3U").assertExists()
                capture("cinema-setup")
                runBlocking {
                    repo.add(
                        "Test · Sinema",
                        SourceSpec(server.url("/").toString(), true, "fixture", "fixture"),
                    )
                    val source = repo.library.snapshot().single { it.name == "Test · Sinema" }
                    val movie =
                        repo.library.browse(source.id, "MOVIE", "", "", "", 20).first().first {
                            it.title == "Açık Deniz"
                        }
                    assertFalse(movie.backdrop.contains("cinema.invalid"))
                    assertEquals("https://cinema.invalid/coast.jpg", repo.backdrop(movie))
                    val channel = repo.library.browse(source.id, "LIVE", "", "", "", 1).first().single()
                    val now = System.currentTimeMillis()
                    repo.guide.put(Feed("home-test-guide", source.id, ""))
                    repo.guide.slots(listOf(Slot("home-test-guide", "news", now - 60_000,
                        now + 3_600_000, "Günün Haberleri", "", "günün haberleri")))
                    repo.guide.map(Mapping(channel.id, "home-test-guide", "news"))
                }
                section("Ana Sayfa")
                focus(node("Oynat"))
                screen.onNodeWithTag("nav-closed").assertExists()
                capture("cinema-home")
                capture("screens/home")
                screen.onNodeWithText("Canlı TV’ye git").assertDoesNotExist()
                capture("screens/nav-closed")
                screen.dpad(Key.DirectionLeft)
                waitFor(hasTestTag("nav-open"))
                screen.onNodeWithTag("nav-open").assertExists()
                listOf("Ara", "Ana Sayfa", "Canlı TV", "Filmler", "Diziler", "Listem", "Ayarlar")
                    .forEach { screen.onNode(navigation(it)).assertExists() }
                capture("screens/nav-open")
                screen.dpad(Key.DirectionRight)
                waitFor(hasText("Oynat") and isFocused())
                screen.onNodeWithTag("nav-closed").assertExists()
                node("Oynat").assertIsFocused()
                screen.dpad(Key.DirectionLeft)
                waitFor(hasTestTag("nav-open"))
                screen.back()
                waitFor(hasText("Oynat") and isFocused())
                screen.onNodeWithTag("nav-closed").assertExists()
                node("Oynat").assertIsFocused()
                // OK on a home channel card must open fullscreen without a detail step.
                focus(screen.onNode(describedIn("Test Haber", "channel-shelf-Şu an yayında — favori kanallar")))
                screen.ok()
                screen.waitUntil(30_000) {
                    runBlocking { preferences.recentLive.first().isNotEmpty() }
                }
                screen.onNodeWithContentDescription("Tam ekran oynatıcı").assertExists()
                // Let the opening banner expire: while visible, Back only dismisses the banner.
                // FullscreenTest separately verifies that two-step Back contract.
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithTag("fullscreen-banner").fetchSemanticsNodes().isEmpty()
                }
                screen.back()
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithContentDescription("Tam ekran oynatıcı").fetchSemanticsNodes().isEmpty()
                }
                node("Son izlenen kanallar").assertExists()
                // Let Home's entry focus settle on the hero before entering the shelves.
                // RequestFocus on a card can race the enclosing focus restorers.
                focus(node("Oynat"))
                val recentCard = describedIn("Test Haber", "channel-shelf-Son izlenen kanallar")
                screen.onNodeWithTag("home-shelves").performScrollToNode(recentCard)
                screen.onNode(recentCard).assertIsDisplayed()
                screen.dpad(Key.DirectionDown)
                // A shelf restorer may re-enter the previously visited on-air row.
                // Move upward through the shelves instead of requesting card focus again.
                for (step in 0 until 6) {
                    if (screen.onAllNodes(recentCard and isFocused())
                            .fetchSemanticsNodes().isNotEmpty()) break
                    screen.dpad(Key.DirectionUp)
                }
                screen.onNode(recentCard).assertIsFocused()
                node("Günün Haberleri").assertExists()
                capture("screens/home-shelves")
                // The card in the recent shelf is the channel that actually started playback.
                val recentId = runBlocking { preferences.recentLive.first().first() }
                runBlocking {
                    assertEquals("Test Haber", repo.library.liveByIds("", listOf(recentId))
                        .first().single().title)
                }
                screen.onNodeWithTag("home-shelves")
                    .performScrollToNode(hasText("Son eklenen filmler"))
                val movieShelf = "shelf-Son eklenen filmler"
                val movieCard = describedIn("Kadife Gece", movieShelf)
                screen.onNodeWithTag("shelf-Son eklenen filmler")
                    .performScrollToNode(movieCard)
                assertTrue(
                    "Afiş adı ekranın içinde kalmalı",
                    screen.onNode(hasText("Kadife Gece") and hasAnyAncestor(hasTestTag(movieShelf)))
                        .fetchSemanticsNode().boundsInRoot.bottom <=
                        screen.onRoot().fetchSemanticsNode().boundsInRoot.bottom,
                )
                focus(screen.onNode(movieCard))
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithText("Kadife Gece").fetchSemanticsNodes().size >= 2
                }
                capture("cinema-focus")
                screen.ok()
                node("+ Listem").assertExists()
                node("Oynat").assertIsFocused()
                capture("cinema-details")
                capture("screens/details")
                select("+ Listem")
                node("✓ Listem").assertExists()
                screen.back()
                section("Filmler")
                focusFilters()
                capture("cinema-movies")
                capture("screens/movies")
                screen.ok()
                waitFor(hasTestTag("filter-panel"))
                node("Yıl").assertExists()
                val movieResult = describedIn("Açık Deniz", "section-Filmler")
                waitFor(movieResult)
                screen.onAllNodes(hasSetTextAction())[0].performTextReplacement("1999")
                screen.back()
                waitFor(hasText("Filtrele (1)") and isFocused())
                screen.onNodeWithTag("filter-panel").assertDoesNotExist()
                node("Eşleşen içerik yok.").assertExists()
                select("Filtrele (1)")
                node("Yıl").assertExists()
                screen.onAllNodes(hasSetTextAction())[0].performTextReplacement("2025")
                screen.waitUntil(30_000) {
                    screen
                        .onAllNodes(movieResult)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                screen.back()
                waitFor(movieResult)
                section("Diziler")
                // Filters belong to each section: Series starts with its own closed panel.
                screen.onNodeWithText("Yıl").assertDoesNotExist()
                focusFilters()
                screen.ok()
                node("Yıl").assertExists()
                screen.onAllNodes(hasSetTextAction())[0].assertTextEquals("")
                screen.back()
                node("Eşleşen içerik yok.").assertExists()
                screen.onNode(describedIn("Açık Deniz", "section-Diziler")).assertDoesNotExist()
                section("Listem")
                val favoritesTab = describedIn("Favoriler", "section-Listem")
                val watchlistTab = describedIn("İzleme Listem", "section-Listem")
                waitFor(favoritesTab and isSelected())
                screen.onNode(favoritesTab).assertIsSelected()
                focus(screen.onNode(watchlistTab))
                screen.ok()
                waitFor(watchlistTab and isSelected())
                screen.onNode(watchlistTab).assertIsSelected()
                screen.waitUntil(30_000) {
                    screen
                        .onAllNodes(describedIn("Kadife Gece", "section-Listem"))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                screen.onNode(describedIn("Açık Deniz", "section-Listem")).assertDoesNotExist()
                section("Filmler")
                select("Filtrele (1)")
                node("Yıl").assertExists()
                screen.onAllNodes(hasSetTextAction())[0].assertTextEquals("2025")
                screen.back()
                section("Ara")
                screen.onNode(hasSetTextAction()).performTextReplacement("Deniz")
                searchShelf("search-shelf-Filmler")
                waitFor(hasContentDescription("Açık Deniz") and
                    hasAnyAncestor(hasTestTag("search-shelf-Filmler")))
                waitAbsent(hasTestTag("channel-shelf-Kanallar"))
                screen.onNodeWithTag("channel-shelf-Kanallar").assertDoesNotExist()
                capture("screens/search")
                screen.onNode(hasSetTextAction()).performTextReplacement("Test Haber")
                searchShelf("channel-shelf-Kanallar")
                waitFor(hasContentDescription("Test Haber") and
                    hasAnyAncestor(hasTestTag("channel-shelf-Kanallar")))
                waitAbsent(hasTestTag("search-shelf-Filmler"))
                screen.onNodeWithTag("search-shelf-Filmler").assertDoesNotExist()
                section("Ayarlar")
                node("IPTV Kaynakları").assertExists()
                screen.dpad(Key.DirectionRight)
                waitFor(hasText("IPTV Kaynakları") and isFocused())
                node("IPTV Kaynakları").assertIsFocused()
                screen.ok()
                node("✓ M3U").assertExists()
                screen.back()
                node("Varsayılan motor · Media3").assertExists()
            } finally {
                runBlocking {
                    preferences.set("recent_live", previousRecent)
                    preferences.set("engine", previousEngine)
                    repo.library
                        .snapshot()
                        .filter { it.name == "Test · Sinema" }
                        .forEach { repo.remove(it.id) }
                }
                Coil.setImageLoader(previous)
                loader.shutdown()
            }
        }
    }
}
