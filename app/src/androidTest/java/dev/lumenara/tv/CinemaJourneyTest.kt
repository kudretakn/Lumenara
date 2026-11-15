package dev.lumenara.tv

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.SuccessResult
import dev.lumenara.data.Feed
import dev.lumenara.data.Mapping
import dev.lumenara.data.Slot
import dev.lumenara.data.Preferences
import dev.lumenara.data.Repository
import dev.lumenara.data.SourceSpec
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
                "Sessiz Liman",
            )
        val art = titles.indices.map { "tile${it % 7}" }
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
        fun section(name: String) {
            if (name == "Ana Sayfa") screen.returnToHub() else screen.openHubCard(name)
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
        fun filterYear() {
            waitFor(hasTestTag("filter-panel"))
            screen.onNodeWithTag("filter-panel").assertIsDisplayed()
            screen.onNodeWithTag("filter-fields").performScrollToNode(hasText("Yıl"))
            node("Yıl").assertIsDisplayed()
        }
        fun capture(name: String) {
            // A real device screenshot includes GPU-composited image transitions and TV focus
            // rings.
            val readyAt = android.os.SystemClock.uptimeMillis() + 1300
            screen.waitUntil(30_000) { android.os.SystemClock.uptimeMillis() >= readyAt }
            val file = File(context.getExternalFilesDir(null), "$name.png")
            checkNotNull(file.parentFile).mkdirs()
            assertTrue("Ekran görüntüsü kaydedilmeli: $name", device.takeScreenshot(file))
            device.executeShellCommand("mkdir -p /sdcard/Download/lumenara-ui")
            device.executeShellCommand(
                "cp ${context.getExternalFilesDir(null)}/$name.png /sdcard/Download/lumenara-ui/${file.name}"
            )
        }
        MockWebServer().use { server ->
            server.start()
            val video = test.context.assets.open("lumenara-test.mp4").use { it.readBytes() }
            val preferences = Preferences(context)
            val previousRecent = runBlocking { preferences.text("recent_live").first() }
            val previousEngine = runBlocking { preferences.text("engine", "Media3").first() }
            runBlocking {
                repo.library.snapshot().filter { it.name == "Test · Sinema" }
                    .forEach { repo.remove(it.id) }
            }
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
                            if (i == 0) JSONArray().put("https://cinema.invalid/backdrop.jpg")
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
                                "get_series_categories" -> """[{"category_id":3,"category_name":"Dram"}]"""
                                "get_series" -> """[{"series_id":301,"name":"Kıyı Günlüğü","category_id":"3","year":"2024"}]"""
                                "get_live_categories" ->
                                    """[{"category_id":2,"category_name":"Haber"}]"""
                                "get_live_streams" ->
                                    """[{"stream_id":101,"name":"Test Haber","category_id":"2","stream_icon":"https://cinema.invalid/channel.jpg"}]"""
                                else -> "[]"
                            }
                        )
                    }
                }
            try {
                node("Liste adresi gir").assertExists()
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
                    assertEquals("https://cinema.invalid/backdrop.jpg", repo.backdrop(movie))
                    val channel = repo.library.browse(source.id, "LIVE", "", "", "", 1).first().single()
                    val now = System.currentTimeMillis()
                    repo.guide.put(Feed("home-test-guide", source.id, ""))
                    repo.guide.slots(listOf(Slot("home-test-guide", "news", now - 60_000,
                        now + 3_600_000, "Günün Haberleri", "", "günün haberleri")))
                    repo.guide.map(Mapping(channel.id, "home-test-guide", "news"))
                }
                section("Ana Sayfa")
                waitFor(hasTestTag("hub-Canlı TV") and isFocused())
                capture("screens/hub")
                listOf("Canlı TV", "Filmler", "Diziler", "Listem", "Ara", "Ayarlar")
                    .forEach { screen.onNodeWithTag("hub-$it").assertExists() }
                section("Canlı TV")
                screen.openCategory("Haber")
                waitFor(hasContentDescription("Test Haber") and isFocused())
                screen.ok()
                screen.waitUntil(30_000) { runBlocking { preferences.recentLive.first().isNotEmpty() } }
                screen.onNodeWithContentDescription("Tam ekran oynatıcı").assertExists()
                waitAbsent(hasTestTag("fullscreen-banner"))
                screen.back()
                waitAbsent(hasContentDescription("Tam ekran oynatıcı"))
                section("Ana Sayfa")
                screen.onNodeWithTag("hub").performScrollToNode(hasTestTag("channel-shelf-Son kanal"))
                val recentCard = describedIn("Test Haber", "channel-shelf-Son kanal")
                waitFor(recentCard)
                node("Günün Haberleri").assertExists()
                capture("screens/home-shelves")
                val recentId = runBlocking { preferences.recentLive.first().first() }
                runBlocking {
                    assertEquals("Test Haber", repo.library.liveByIds("", listOf(recentId)).first().single().title)
                }
                section("Filmler")
                screen.openCategory("Sinema")
                val movieCard = describedIn("Kadife Gece", "poster-grid")
                screen.onNodeWithTag("poster-grid").performScrollToNode(movieCard)
                focus(screen.onNode(movieCard))
                val posterBounds = screen.onNode(movieCard).fetchSemanticsNode().boundsInRoot
                assertEquals("Afiş oranı 2:3 kalmalı", 2f / 3f, posterBounds.width / posterBounds.height, .02f)
                val gridCards = screen.onAllNodes(hasContentDescription("Açık Deniz") or
                    hasContentDescription("Kızıl Ufuk") or hasContentDescription("Kumların Ötesi") or
                    hasContentDescription("Gece Hattı") or hasContentDescription("Mavi Sessizlik") or
                    hasContentDescription("Son Sefer") or hasContentDescription("Kadife Gece") or
                    hasContentDescription("Sessiz Liman"))
                    .fetchSemanticsNodes().filter { it.boundsInRoot.width > 0 }
                assertEquals("Sekiz afiş ilk satıra sığmalı", 8, gridCards.size)
                assertTrue(gridCards.maxOf { it.boundsInRoot.top } - gridCards.minOf { it.boundsInRoot.top } < 20f)
                assertTrue("Afiş adı ekranın içinde kalmalı",
                    screen.onNode(hasText("Kadife Gece") and hasAnyAncestor(hasTestTag("poster-grid")))
                        .fetchSemanticsNode().boundsInRoot.bottom <= screen.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
                capture("cinema-focus")
                screen.ok()
                node("Listem").assertExists()
                node("Oynat").assertIsFocused()
                capture("cinema-details")
                capture("screens/details")
                select("Listem")
                waitFor(hasText("Listem") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                screen.back()
                section("Filmler")
                screen.openCategory("Sinema")
                focusFilters()
                capture("cinema-movies")
                capture("screens/movies")
                screen.ok()
                filterYear()
                val movieResult = describedIn("Açık Deniz", "section-Filmler")
                waitFor(movieResult)
                screen.onAllNodes(hasSetTextAction())[0].performTextReplacement("1999")
                screen.back()
                waitFor(hasText("Filtrele (1)") and isFocused())
                screen.onNodeWithTag("filter-panel").assertDoesNotExist()
                node("Eşleşen içerik yok.").assertExists()
                select("Filtrele (1)")
                filterYear()
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
                screen.openCategory("Tümü")
                // Filters belong to each section: Series starts with a closed, empty panel.
                screen.onNodeWithText("Yıl").assertDoesNotExist()
                focusFilters()
                screen.ok()
                filterYear()
                screen.onAllNodes(hasSetTextAction())[0].assertTextEquals("")
                screen.onAllNodes(hasSetTextAction())[0].performTextReplacement("1999")
                screen.back()
                waitFor(hasText("Eşleşen içerik yok."))
                screen.onNode(describedIn("Açık Deniz", "section-Diziler")).assertDoesNotExist()
                section("Listem")
                screen.openCategory("İzleme listem")
                screen.waitUntil(30_000) {
                    screen
                        .onAllNodes(describedIn("Kadife Gece", "section-Listem"))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                screen.onNode(describedIn("Açık Deniz", "section-Listem")).assertDoesNotExist()
                screen.back()
                waitFor(hasTestTag("category-İzleme listem") and isFocused())
                screen.openCategory("Favoriler")
                waitFor(hasText("Eşleşen içerik yok."))
                screen.onNode(describedIn("Kadife Gece", "section-Listem")).assertDoesNotExist()
                section("Filmler")
                screen.openCategory("Sinema")
                select("Filtrele (1)")
                filterYear()
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
                waitFor(hasText("IPTV Kaynakları") and isFocused())
                node("IPTV Kaynakları").assertIsFocused()
                screen.ok()
                node("Liste adresi gir").assertExists()
                screen.back()
                screen.onNodeWithTag("settings-group-Oynatıcı").performClick()
                screen.onNodeWithTag("settings-list").performScrollToNode(hasText("Varsayılan motor · Media3"))
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
