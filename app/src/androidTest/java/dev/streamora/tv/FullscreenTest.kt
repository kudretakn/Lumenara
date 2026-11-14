package dev.streamora.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.streamora.data.*
import dev.streamora.design.StreamoraTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FullscreenTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun media3MiniGuideChangesChannelWithoutLeavingFullscreen() = journey("Media3", 10_000)
    @Test fun vlcMiniGuideUsesTheSameRemoteContract() = journey("VLC", 2)

    private fun journey(engine: String, count: Int) {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val device = UiDevice.getInstance(test)
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val previousEngine = runBlocking { prefs.text("engine", "Media3").first() }
        val previousFallback = runBlocking { prefs.text("fallback", "Açık").first() }
        val previousRecent = runBlocking { prefs.text("recent_live").first() }
        val name = "Test · Tam ekran $engine"
        val bytes = test.context.assets.open("streamora-test.mp4").use { it.readBytes() }
        fun await(matcher: SemanticsMatcher, present: Boolean = true) {
            screen.waitUntil(30_000) {
                screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() == present
            }
        }
        fun shot(filename: String) {
            val file = File(context.getExternalFilesDir(null), "screens/$filename.png")
            checkNotNull(file.parentFile).mkdirs()
            assertTrue(device.takeScreenshot(file))
        }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/list") return MockResponse().setBody(buildString {
                        appendLine("#EXTM3U")
                        repeat(count) {
                            appendLine("#EXTINF:-1 group-title=\"Test\",Kanal %05d".format(it))
                            appendLine(server.url("/live/$it"))
                        }
                    })
                    val start = request.getHeader("Range")?.substringAfter("bytes=")
                        ?.substringBefore("-")?.toIntOrNull() ?: 0
                    if (start >= bytes.size) return MockResponse().setResponseCode(416)
                    return MockResponse().setResponseCode(if (start > 0) 206 else 200)
                        .setHeader("Content-Type", "video/mp4")
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                        .setBody(Buffer().write(bytes, start, bytes.size - start))
                }
            }
            server.start()
            try {
                val channels = runBlocking {
                    prefs.set("engine", engine)
                    prefs.set("fallback", "Kapalı")
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                    val source = repo.library.snapshot().single { it.name == name }
                    repo.library.browse(source.id, "LIVE", "", "", "", Int.MAX_VALUE).first()
                }
                assertEquals(count, channels.size)
                screen.runOnUiThread {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        StreamoraTheme {
                            var selected by remember { mutableStateOf(channels.first()) }
                            var group by remember { mutableStateOf(channels) }
                            var exited by remember { mutableStateOf(false) }
                            Box(Modifier.fillMaxSize().testTag("fullscreen-test-host").semantics {
                                stateDescription = if (exited) "Kapandı" else selected.id
                            }) {
                                if (!exited) Playback(model, selected, group, { selected = it },
                                    { exited = true }, { group = it })
                            }
                        }
                    }
                }
                await(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                device.pressDPadLeft()
                await(hasTestTag("mini-guide"))
                await(hasTestTag("live-channel-${channels[0].id}") and isFocused())
                // LazyColumn composes only visible rows, even with the full 10,000-channel catalog.
                if (count > 100) assertTrue(screen.onAllNodes(hasTestTag("live-channel-${channels.last().id}"))
                    .fetchSemanticsNodes().isEmpty())
                shot(if (engine == "Media3") "mini-guide" else "mini-guide-vlc")
                device.pressDPadDown()
                await(hasTestTag("live-channel-${channels[1].id}") and isFocused())
                device.pressDPadCenter()
                await(hasTestTag("mini-guide"), false)
                await(hasTestTag("fullscreen-test-host") and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, channels[1].id))
                await(hasTestTag("fullscreen-banner"))
                shot(if (engine == "Media3") "fullscreen-banner" else "fullscreen-banner-vlc")
                device.pressBack()
                await(hasTestTag("fullscreen-banner"), false)
                // A second OK while the banner is visible opens the guide; Back dismisses it.
                device.pressDPadCenter()
                await(hasTestTag("fullscreen-banner"))
                device.pressDPadCenter()
                await(hasTestTag("mini-guide"))
                device.pressBack()
                await(hasTestTag("mini-guide"), false)
                // Deadlines use wall time and remain observable while Compose is idle.
                device.pressDPadCenter()
                await(hasTestTag("fullscreen-banner"))
                await(hasTestTag("fullscreen-banner"), false)
                device.pressDPadLeft()
                await(hasTestTag("mini-guide"))
                await(hasTestTag("mini-guide"), false)
                await(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                device.pressMenu()
                await(hasText("Yayına dön"))
                device.pressBack()
                await(hasText("Yayına dön"), false)
                device.pressBack()
                await(hasTestTag("fullscreen-test-host") and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Kapandı"))
            } finally {
                screen.runOnUiThread { screen.activity.setContent {} }
                runBlocking {
                    prefs.set("engine", previousEngine)
                    prefs.set("fallback", previousFallback)
                    prefs.set("recent_live", previousRecent)
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                }
            }
        }
    }
}
