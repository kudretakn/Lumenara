package dev.streamora.tv

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
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
import dev.streamora.data.Repository
import dev.streamora.data.SourceSpec
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
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
            screen.waitUntil(10000) {
                screen.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
            return screen.onAllNodesWithText(text).onFirst()
        }
        fun focus(node: SemanticsNodeInteraction) {
            node.performSemanticsAction(SemanticsActions.RequestFocus)
            screen.waitForIdle()
        }
        fun select(text: String) {
            focus(node(text))
            device.pressDPadCenter()
            screen.waitForIdle()
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
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse()
                            .setBody(
                                when (request.requestUrl?.queryParameter("action")) {
                                    null -> """{"user_info":{"auth":1}}"""
                                    "get_vod_categories" ->
                                        """[{"category_id":1,"category_name":"Sinema"}]"""
                                    "get_vod_streams" -> catalog.toString()
                                    else -> "[]"
                                }
                            )
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
                }
                focus(screen.onNodeWithContentDescription("Ana Sayfa"))
                device.pressDPadCenter()
                focus(node("Oynat"))
                screen.onNodeWithTag("nav-closed").assertExists()
                capture("cinema-home")
                capture("screens/nav-closed")
                device.pressDPadLeft()
                screen.waitForIdle()
                screen.onNodeWithTag("nav-open").assertExists()
                listOf("Ara", "Ana Sayfa", "Canlı TV", "Filmler", "Diziler", "Listem", "Ayarlar")
                    .forEach { screen.onNodeWithContentDescription(it).assertExists() }
                capture("screens/nav-open")
                device.pressDPadRight()
                screen.waitForIdle()
                screen.onNodeWithTag("nav-closed").assertExists()
                node("Oynat").assertIsFocused()
                device.pressDPadLeft()
                screen.waitForIdle()
                device.pressBack()
                screen.waitForIdle()
                screen.onNodeWithTag("nav-closed").assertExists()
                node("Oynat").assertIsFocused()
                assertTrue(
                    "Afiş adı ekranın içinde kalmalı",
                    node("Kadife Gece").fetchSemanticsNode().boundsInRoot.bottom <=
                        screen.onRoot().fetchSemanticsNode().boundsInRoot.bottom,
                )
                screen
                    .onNodeWithContentDescription("Kadife Gece")
                    .performSemanticsAction(SemanticsActions.RequestFocus)
                screen.waitForIdle()
                screen.waitUntil(5000) {
                    screen.onAllNodesWithText("Kadife Gece").fetchSemanticsNodes().size >= 2
                }
                capture("cinema-focus")
                device.pressDPadCenter()
                node("İzleme listeme ekle").assertExists()
                capture("cinema-details")
                select("İzleme listeme ekle")
                node("Listeden çıkar").assertExists()
                device.pressBack()
                focus(screen.onNodeWithContentDescription("Filmler"))
                device.pressDPadCenter()
                focus(node("İçerik filtreleri"))
                capture("cinema-movies")
                device.pressDPadCenter()
                node("Yıl").assertExists()
                screen.onAllNodes(hasSetTextAction())[0].performTextReplacement("1999")
                node("Eşleşen içerik yok.").assertExists()
                screen.onAllNodes(hasSetTextAction())[0].performTextReplacement("2025")
                screen.waitUntil(5000) {
                    screen
                        .onAllNodesWithContentDescription("Açık Deniz")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                focus(screen.onNodeWithContentDescription("Diziler"))
                device.pressDPadCenter()
                node("Eşleşen içerik yok.").assertExists()
                focus(screen.onNodeWithContentDescription("Listem"))
                device.pressDPadCenter()
                screen.waitForIdle()
                screen.onNodeWithContentDescription("Favoriler").assertIsSelected()
                focus(screen.onNodeWithContentDescription("İzleme Listem"))
                device.pressDPadCenter()
                screen.waitForIdle()
                screen.onNodeWithContentDescription("İzleme Listem").assertIsSelected()
                screen.waitUntil(5000) {
                    screen
                        .onAllNodesWithContentDescription("Kadife Gece")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                screen.onNodeWithContentDescription("Açık Deniz").assertDoesNotExist()
                focus(screen.onNodeWithContentDescription("Filmler"))
                device.pressDPadCenter()
                node("Yıl").assertExists()
                screen.onAllNodes(hasSetTextAction())[0].assertTextEquals("2025")
                focus(screen.onNodeWithContentDescription("Ayarlar"))
                device.pressDPadCenter()
                node("IPTV Kaynakları").assertExists()
                device.pressDPadRight()
                screen.waitForIdle()
                node("IPTV Kaynakları").assertIsFocused()
                device.pressDPadCenter()
                node("✓ M3U").assertExists()
                device.pressBack()
                node("Varsayılan motor · Media3").assertExists()
            } finally {
                runBlocking {
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
