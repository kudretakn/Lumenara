package dev.streamora.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.streamora.data.*
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
    fun addPlaylistFromSetupPreviewAndOpenChannelPanel() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val device = UiDevice.getInstance(test)
        val repo = Repository.get(context)
        val name = "Test · Kullanım"
        val preferences = Preferences(context)
        val previousRecent = runBlocking { preferences.text("recent_live").first() }
        val previousEngine = runBlocking { preferences.text("engine", "Media3").first() }
        runBlocking { preferences.set("engine", "Media3") }
        val received = CountDownLatch(1)
        MockWebServer().use { server ->
            server.start()
            val bytes = test.context.assets.open("streamora-test.mp4").use { it.readBytes() }
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/list")
                            return MockResponse()
                                .setBody(
                                    "#EXTM3U\n#EXTINF:-1 group-title=\"Spor\",Test Kanalı\n${server.url("/live/1")}\n"
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
            try {
                button("✓ M3U")
                val fields = screen.onAllNodes(hasSetTextAction())
                fields[0].performTextReplacement(name)
                fields[1].performTextReplacement(server.url("/list").toString())
                select("HTTP uyumluluğu · Kapalı")
                button("HTTP uyumluluğu · Açık").assertExists()
                select("Bağlantıyı sına ve ekle")
                button("Şu an yayında — favori kanallar").assertExists()
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
                    item
                }
                screen.onNodeWithContentDescription("Canlı TV")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasContentDescription("Canlı TV") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screen.onNodeWithTag("nav-open").assertExists()
                screen.ok()
                button("Yayın çizelgesi").assertExists()
                screen.dpad(Key.DirectionRight)
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithTag("nav-closed").fetchSemanticsNodes().isNotEmpty()
                }
                screen.onNodeWithTag("nav-closed").assertExists()
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithTag("live-category-Spor").fetchSemanticsNodes().isNotEmpty()
                }
                screen.onNodeWithTag("live-category-Spor")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasTestTag("live-category-Spor") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screen.ok()
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasTestTag("live-category-Spor") and isSelected())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screen.onNodeWithTag("live-category-Spor").assertIsSelected()
                screen.waitUntil(30_000) {
                    screen.onAllNodesWithTag("live-channel-${channel.id}").fetchSemanticsNodes().isNotEmpty()
                }
                val row = screen.onNodeWithTag("live-channel-${channel.id}")
                // Enter the channel column through the same remote path as the user.
                screen.dpad(Key.DirectionRight)
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasTestTag("live-channel-${channel.id}") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                row.assertIsFocused()
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasTestTag("live-preview") and
                        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Oynatılıyor"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(0L, received.count)
                button("Sonra: Maç Sonu").assertExists()
                button("Tam ekran için OK").assertExists()
                row.assertIsFocused()
                screen.onNode(hasTestTag("live-preview-panel") and isFocusable()).assertDoesNotExist()
                val image = File(context.getExternalFilesDir(null), "screens/live.png")
                checkNotNull(image.parentFile).mkdirs()
                assertTrue("Canlı TV ekran görüntüsü", device.takeScreenshot(image))
                screen.ok()
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
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
                button("Yayın çizelgesi")
                screen.waitUntil(30_000) {
                    screen.onAllNodes(hasTestTag("live-channel-${channel.id}") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                // Long OK keeps the existing favorite/EPG operations reachable.
                screen.onNodeWithTag("live-channel-${channel.id}").assertIsFocused()
                screen.longPressOkNative()
                button("Favoriye ekle").assertExists()
                screen.back()
                select("Yayın çizelgesi")
                button("Kanallar").assertExists()
                screen.back()
                button("Yayın çizelgesi").assertExists()
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
