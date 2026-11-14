package dev.streamora.tv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.streamora.data.Repository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                    screen.waitUntil(10000) {
                        screen.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (e: ComposeTimeoutException) {
                    throw AssertionError("Beklenen: $label\n" + screen.onRoot().printToString(), e)
                }
                return screen.onAllNodesWithText(label).onFirst()
            }
            fun select(label: String) {
                button(label)
                    .assertIsEnabled()
                    .performSemanticsAction(
                        androidx.compose.ui.semantics.SemanticsActions.RequestFocus
                    )
                screen.waitForIdle()
                device.pressDPadCenter()
            }
            try {
                button("✓ M3U")
                val fields = screen.onAllNodes(hasSetTextAction())
                fields[0].performTextReplacement(name)
                fields[1].performTextReplacement(server.url("/list").toString())
                select("HTTP uyumluluğu · Kapalı")
                button("HTTP uyumluluğu · Açık").assertExists()
                select("Bağlantıyı sına ve ekle")
                button("Canlı TV’ye git").assertExists()
                screen.onNodeWithContentDescription("Canlı TV")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                screen.waitForIdle()
                screen.onNodeWithTag("nav-open").assertExists()
                device.pressDPadCenter()
                button("Yayın çizelgesi").assertExists()
                device.pressDPadRight()
                screen.waitForIdle()
                screen.onNodeWithTag("nav-closed").assertExists()
                select("Test Kanalı")
                button("Tam ekran").assertExists()
                screen.waitUntil(15000) { received.count == 0L }
                select("Tam ekran")
                screen.waitUntil(10000) {
                    screen.onAllNodes(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                device.pressDPadCenter()
                button("Yayına dön")
                device.pressBack()
                device.pressBack()
                button("Yayın çizelgesi")
            } finally {
                runBlocking {
                    repo.library
                        .snapshot()
                        .filter { it.name == name }
                        .forEach { repo.remove(it.id) }
                }
            }
        }
    }
}
