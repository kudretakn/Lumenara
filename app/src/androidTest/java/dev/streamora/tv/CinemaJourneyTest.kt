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
        fun section(name: String) {
            focus(screen.onNodeWithContentDescription(name))
            // Dispatch through the Compose root so native input delivery cannot race the next query.
            screen.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            screen.waitUntil(30_000) {
                screen.onAllNodesWithTag("section-$name").fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun waitFor(matcher: SemanticsMatcher) {
            screen.waitUntil(30_000) {
                screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun select(text: String) {
            focus(node(text))
            device.pressDPadCenter()
        }
        fun capture(name: String) {
            // A real device screenshot includes GPU-composited image transitions and TV focus
            // rings.
            Thread.sleep(1300)
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
                device.pressDPadLeft()
                waitFor(hasTestTag("nav-open"))
                screen.onNodeWithTag("nav-open").assertExists()
                listOf("Ara", "Ana Sayfa", "Canlı TV", "Filmler", "Diziler", "Listem", "Ayarlar")
                    .forEach { screen.onNodeWithContentDescription(it).assertExists() }
                capture("screens/nav-open")
                device.pressDPadRight()
                waitFor(hasText("Oynat") and isFocused())
                screen.onNodeWithTag("nav-closed").assertExists()
                node("Oynat").assertIsFocused()
                device.pressDPadLeft()
                waitFor(hasTestTag("nav-open"))
                device.pressBack()
                waitFor(hasText("Oynat") and isFocused())
                screen.onNodeWithTag("nav-closed").assertExists()
                node("Oynat").assertIsFocused()
                // OK on a home channel card must open fullscreen without a detail step.
                focus(screen.onNodeWithContentDescription("Test Haber"))
                device.pressDPadCenter()
                screen.waitUntil(30_000) {
                    runBlocking { preferences.recentLive.first().isNotEmpty() }
                }
                screen.onNodeWithContentDescription("Tam ekran oynatıcı").assertExists()
                device.pressBack()
                node("Son izlenen kanallar").assertExists()
                screen.onNodeWithTag("home-shelves")
                    .performScrollToNode(hasText("Son izlenen kanallar"))
                focus(screen.onNode(hasContentDescription("Test Haber") and
                    hasAnyAncestor(hasTestTag("channel-shelf-Son izlenen kanallar"))))
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
                screen.onNodeWithTag("shelf-Son eklenen filmler")
                    .performScrollToNode(hasContentDescription("Kadife Gece"))
                assertTrue(
                    "Afiş adı ekranın içinde kalmalı",
                    node("Kadife Gece").fetchSemanticsNode().boundsInRoot.bottom <=
                        screen.onRoot().fetchSemanticsNode().boundsInRoot.bottom,
                )
                screen
                    .onNodeWithContentDescription("Kadife Gece")
                    .performSemanticsAction(SemanticsActions.RequestFocus)
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithText("Kadife Gece").fetchSemanticsNodes().size >= 2
                }
                capture("cinema-focus")
                device.pressDPadCenter()
                node("İzleme listeme ekle").assertExists()
                capture("cinema-details")
                select("İzleme listeme ekle")
                node("Listeden çıkar").assertExists()
                device.pressBack()
                section("Filmler")
                focus(node("İçerik filtreleri"))
                capture("cinema-movies")
                device.pressDPadCenter()
                node("Yıl").assertExists()
                waitFor(hasContentDescription("Açık Deniz"))
                screen.onAllNodes(hasSetTextAction())[0].performTextReplacement("1999")
                node("Eşleşen içerik yok.").assertExists()
                screen.onAllNodes(hasSetTextAction())[0].performTextReplacement("2025")
                screen.waitUntil(30_000) {
                    screen
                        .onAllNodesWithContentDescription("Açık Deniz")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                section("Diziler")
                // Filters belong to each section: Series starts with its own closed panel.
                screen.onNodeWithText("Yıl").assertDoesNotExist()
                select("İçerik filtreleri")
                node("Yıl").assertExists()
                screen.onAllNodes(hasSetTextAction())[0].assertTextEquals("")
                node("Eşleşen içerik yok.").assertExists()
                screen.onNodeWithContentDescription("Açık Deniz").assertDoesNotExist()
                section("Listem")
                waitFor(hasContentDescription("Favoriler") and isSelected())
                screen.onNodeWithContentDescription("Favoriler").assertIsSelected()
                focus(screen.onNodeWithContentDescription("İzleme Listem"))
                device.pressDPadCenter()
                waitFor(hasContentDescription("İzleme Listem") and isSelected())
                screen.onNodeWithContentDescription("İzleme Listem").assertIsSelected()
                screen.waitUntil(30_000) {
                    screen
                        .onAllNodesWithContentDescription("Kadife Gece")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                screen.onNodeWithContentDescription("Açık Deniz").assertDoesNotExist()
                section("Filmler")
                node("Yıl").assertExists()
                screen.onAllNodes(hasSetTextAction())[0].assertTextEquals("2025")
                section("Ayarlar")
                node("IPTV Kaynakları").assertExists()
                device.pressDPadRight()
                waitFor(hasText("IPTV Kaynakları") and isFocused())
                node("IPTV Kaynakları").assertIsFocused()
                device.pressDPadCenter()
                node("✓ M3U").assertExists()
                device.pressBack()
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
