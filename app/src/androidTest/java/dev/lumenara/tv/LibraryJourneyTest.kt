package dev.lumenara.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.lumenara.data.*
import java.io.File
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryJourneyTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test
    fun addPlaylistFromSetupBrowseGridAndOpenChannelPanel() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val device = UiDevice.getInstance(test)
        val repo = Repository.get(context)
        val name = "Test · Kullanım"
        val preferences = Preferences(context)
        val previousRecent = runBlocking { preferences.text("recent_live").first() }
        val previousEngine = runBlocking { preferences.text("engine", "Media3").first() }
        runBlocking {
            repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
        }
        runBlocking { preferences.set("engine", "Media3") }
        val received = CountDownLatch(1)
        MockWebServer().use { server ->
            server.start()
            val bytes = test.context.assets.open("lumenara-test.mp4").use { it.readBytes() }
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/list")
                            return MockResponse()
                                .setBody(
                                    "#EXTM3U\n#EXTINF:-1 group-title=\"Spor\",Test Kanalı\n${server.url("/live/1")}\n" +
                                        "#EXTINF:-1 group-title=\"Filmler\",Gece Treni\n${server.url("/movie/1.mp4")}\n"
                                )
                        received.countDown()
                        return MockResponse()
                            .setHeader("Content-Type", "video/mp4")
                            .setBody(Buffer().write(bytes))
                    }
                }
            fun button(label: String): SemanticsNodeInteraction {
                try {
                    screen.waitUntil(30_000) {
                        screen.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (e: ComposeTimeoutException) {
                    throw AssertionError("Beklenen: $label\n" + screen.onRoot().printToString(), e)
                }
                return screen.onAllNodesWithText(label).onFirst()
            }
            fun select(label: String) {
                // LazyColumn does not compose controls below the viewport until scrolled.
                if (screen.onAllNodesWithTag("source-setup").fetchSemanticsNodes().isNotEmpty()) {
                    screen.onNodeWithTag("source-setup").performScrollToNode(hasText(label))
                }
                button(label)
                    .assertIsEnabled()
                    .performSemanticsAction(
                        androidx.compose.ui.semantics.SemanticsActions.RequestFocus
                    )
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasText(label) and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                screen.ok()
            }
            fun capture(name: String) {
                // Include the completed TV focus animation in the device screenshot.
                val readyAt = android.os.SystemClock.uptimeMillis() + 300
                screen.waitUntil(30_000) { android.os.SystemClock.uptimeMillis() >= readyAt }
                val image = File(context.getExternalFilesDir(null), "screens/$name.png")
                checkNotNull(image.parentFile).mkdirs()
                assertTrue("Ekran görüntüsü: $name", device.takeScreenshot(image))
            }
            try {
                select("Liste adresi gir")
                val fields = screen.onAllNodes(hasSetTextAction())
                fields[0].performTextReplacement(name)
                fields[1].performTextReplacement(server.url("/list").toString())
                select("Gelişmiş")
                select("HTTP uyumluluğu · Kapalı")
                button("HTTP uyumluluğu · Açık").assertExists()
                select("Bağlantıyı sına ve ekle")
                button("Canlı TV").assertExists()
                val channel = runBlocking {
                    val source = repo.library.snapshot().single { it.name == name }
                    val item = repo.library.browse(source.id, "LIVE", "", "", "", 1).first().single()
                    val now = System.currentTimeMillis()
                    repo.guide.put(Feed("live-journey", source.id, ""))
                    repo.guide.slots(listOf(
                        Slot("live-journey", "sport", now - 1_800_000, now + 1_800_000,
                            "Canlı Maç", "Günün karşılaşması ve saha içinden gelişmeler.", "canli mac"),
                        Slot("live-journey", "sport", now + 1_800_000, now + 3_600_000,
                            "Maç Sonu", "Karşılaşmanın değerlendirmesi.", "mac sonu"),
                    ))
                    repo.guide.map(Mapping(item.id, "live-journey", "sport"))
                    preferences.set("recent_live", "")
                    preferences.recordLive(item.id)
                    val movie = repo.library.browse(source.id, "MOVIE", "", "", "", 1).first().single()
                    repo.updateMark(movie.id) {
                        it.copy(position = 900_000, duration = 5_400_000, updated = now)
                    }
                    item
                }
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasTestTag("hub-Canlı TV") and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                capture("hub")
                val density = context.resources.displayMetrics.density
                listOf("Canlı TV" to "Listem", "Filmler" to "Ara", "Diziler" to "Ayarlar").forEach { (top, bottom) ->
                    val upper = screen.onNodeWithTag("hub-$top").fetchSemanticsNode().boundsInRoot
                    val lower = screen.onNodeWithTag("hub-$bottom").fetchSemanticsNode().boundsInRoot
                    assertEquals("Hub kolonları aynı merkezde kalmalı", upper.center.x, lower.center.x, density)
                    assertEquals("İkincil kart yüksekliği", 64f, lower.height / density, 1f)
                }
                assertEquals("Ortak sol kolon", 48f,
                    screen.onNodeWithTag("hub-Listem").fetchSemanticsNode().boundsInRoot.left / density, 1f)
                screen.onNodeWithTag("hub").performScrollToNode(hasTestTag("channel-shelf-Son kanal"))
                val recentCard = hasTestTag("live-channel-${channel.id}") and
                    hasAnyAncestor(hasTestTag("channel-shelf-Son kanal"))
                screen.onNode(recentCard).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                screen.waitUntil(30_000) { screen.onAllNodes(recentCard and isFocused()).fetchSemanticsNodes().isNotEmpty() }
                screen.onNodeWithTag("hub").performScrollToNode(hasTestTag("shelf-Kaldığın yerden devam et"))
                button("75 dk kaldı").assertIsDisplayed()
                capture("home-shelves")
                listOf("channel-shelf-Son kanal", "shelf-Kaldığın yerden devam et").forEach { tag ->
                    assertEquals("Raflar aynı sol kolonda başlamalı", 48f,
                        screen.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left / density, 1f)
                }
                assertEquals("Hub rafları yayın başlatmamalı", 1L, received.count)
                screen.openHubCard("Canlı TV")
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithTag("category-Son izlenenler").fetchSemanticsNodes().isNotEmpty()
                }
                val category = screen.onNodeWithTag("category-Son izlenenler").fetchSemanticsNode().boundsInRoot
                assertEquals("Kategori kartı genişliği", 200f, category.width / density, 1f)
                assertEquals("Kategori kartı yüksekliği", 88f, category.height / density, 1f)
                screen.onNodeWithTag("category-search")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                screen.ok()
                screen.onNodeWithTag("category-search-field").performTextReplacement("Spor")
                screen.onNodeWithTag("category-Spor").assertExists()
                screen.onNodeWithTag("category-Tüm kanallar").assertDoesNotExist()
                screen.back()
                screen.onNodeWithTag("category-search").assertIsFocused()
                screen.openCategory("Spor")
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasTestTag("live-channel-${channel.id}") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                val row = screen.onNodeWithTag("live-channel-${channel.id}")
                row.assertIsFocused()
                // Browsing the grid must not start a stream.
                assertEquals(1L, received.count)
                screen.onNodeWithText("Canlı Maç").assertExists()
                screen.onNodeWithTag("live-preview").assertDoesNotExist()
                capture("live")
                screen.ok()
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screen.waitUntil(30_000) { received.count == 0L }
                screen.dpad(Key.DirectionLeft)
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithTag("mini-guide").fetchSemanticsNodes().isNotEmpty()
                }
                screen.back()
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithTag("mini-guide").fetchSemanticsNodes().isEmpty()
                }
                screen.dpad(Key.Menu)
                button("Yayına dön")
                screen.back()
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithText("Yayına dön").fetchSemanticsNodes().isEmpty()
                }
                screen.back()
                button("Spor")
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasTestTag("live-channel-${channel.id}") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                // Long OK keeps the existing favorite/EPG operations reachable.
                screen.onNodeWithTag("live-channel-${channel.id}").assertIsFocused()
                screen.longPressOkNative()
                button("Favoriye ekle").assertExists()
                screen.back()
                screen.back()
                screen.openCategory("Yayın akışı")
                button("Kanallar").assertExists()
                screen.back()
                button("Yayın akışı").assertExists()
            } finally {
                runBlocking {
                    preferences.set("recent_live", previousRecent)
                    preferences.set("engine", previousEngine)
                    repo.library
                        .snapshot()
                        .filter { it.name == name }
                        .forEach { repo.remove(it.id) }
                }
            }
        }
    }
}
