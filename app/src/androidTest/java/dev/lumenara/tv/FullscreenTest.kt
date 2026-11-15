package dev.lumenara.tv

import androidx.compose.ui.input.key.Key
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.lumenara.data.*
import dev.lumenara.design.LumenaraTheme
import dev.lumenara.design.LumenColors
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
        val previousAdult = runBlocking { prefs.showAdult.first() }
        val previousEngine = runBlocking { prefs.text("engine", "Media3").first() }
        val previousFallback = runBlocking { prefs.text("fallback", "Açık").first() }
        val previousRecent = runBlocking { prefs.text("recent_live").first() }
        val name = "Test · Tam ekran $engine"
        runBlocking {
            repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
        }
        val bytes = test.context.assets.open("lumenara-test.ts").use { it.readBytes() }
        fun await(matcher: SemanticsMatcher, present: Boolean = true) {
            try {
                screen.waitUntil(30_000) {
                    screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() == present
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError(
                    "Beklenen: $matcher, present=$present\n" + screen.onRoot().printToString() +
                        "\ncurrentFocus=${screen.activity.currentFocus?.javaClass?.name}", e
                )
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
                            appendLine(server.url("/live/$it.ts"))
                        }
                        appendLine("#EXTINF:-1 group-title=\"For Adults\",Gizli Kanal")
                        appendLine(server.url("/live/adult.ts"))
                    })
                    if (request.path?.endsWith(".ts") == true) return MockResponse()
                        .setHeader("Content-Type", "video/mp2t")
                        .setBody(Buffer().write(bytes))
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start()
            try {
                val (channels, lastChannelId) = runBlocking {
                    prefs.setShowAdult(false)
                    prefs.set("engine", engine)
                    prefs.set("fallback", "Kapalı")
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                    val source = repo.library.snapshot().single { it.name == name }
                    assertEquals(count, repo.liveCount(source.id).first())
                    val order = kotlinx.coroutines.withTimeout(10_000) {
                        repo.liveChannelOrder(source.id).first { it.size == count }
                    }
                    repo.livePage(source.id, "", "", "", 80, 0).first() to order.last().id
                }
                assertEquals(minOf(80, count), channels.size)
                screen.runOnUiThread {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        LumenaraTheme {
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
                screen.dpad(Key.DirectionLeft)
                await(hasTestTag("mini-guide"))
                screen.onNodeWithContentDescription("For Adults").assertDoesNotExist()
                screen.onNodeWithContentDescription("Gizli Kanal").assertDoesNotExist()
                await(hasTestTag("live-channel-${channels[0].id}") and isFocused())
                val guideBounds = screen.onNodeWithTag("mini-guide").fetchSemanticsNode().boundsInRoot
                val hostBounds = screen.onNodeWithTag("fullscreen-test-host").fetchSemanticsNode().boundsInRoot
                val density = context.resources.displayMetrics.density
                assertEquals("Guide uses the approved 400 dp panel", 400f * density, guideBounds.width, 1f)
                assertEquals("Guide covers the full screen height", hostBounds.height, guideBounds.height, 1f)
                screen.onAllNodes(hasText("Sonra:", substring = true) and
                    hasAnyAncestor(hasTestTag("mini-guide"))).assertCountEquals(0)
                screen.onAllNodes(hasText("Program bilgisi yok") and
                    hasAnyAncestor(hasTestTag("mini-guide"))).assertCountEquals(0)
                if (count >= 8) {
                    val viewport = screen.onNodeWithTag("mini-guide-channels").fetchSemanticsNode().boundsInRoot
                    val completeRows = (viewport.height / (64f * density)).toInt().coerceAtMost(channels.size)
                    assertTrue("At least six complete rows fit at 540 dp", completeRows >= 6)
                    channels.take(completeRows).forEach { channel ->
                        val row = screen.onNodeWithTag("live-channel-${channel.id}")
                        row.assertIsDisplayed()
                        val bounds = row.fetchSemanticsNode().boundsInRoot
                        assertEquals("Guide row stays 64 dp", 64f * density, bounds.height, 1f)
                        assertTrue("Complete rows stay within the viewport",
                            bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
                    }
                }
                // The outer right edge has no controls. Every sampled pixel must be the
                // opaque surface colour, regardless of the video/library underneath it.
                val panelPixels = screen.onNodeWithTag("mini-guide").captureToImage().toPixelMap()
                val rightEdge = panelPixels.width - 1
                for (y in 2 until panelPixels.height - 2 step 13) {
                    assertEquals("No backdrop leaks through the guide at y=$y",
                        LumenColors.surface1, panelPixels[rightEdge, y])
                }
                // SQL pages and LazyColumn both stay bounded with 10,000 channels.
                if (count > 100) assertTrue(screen.onAllNodes(hasTestTag("live-channel-$lastChannelId"))
                    .fetchSemanticsNodes().isEmpty())
                shot(if (engine == "Media3") "mini-guide" else "mini-guide-vlc")
                screen.dpad(Key.DirectionDown)
                await(hasTestTag("live-channel-${channels[1].id}") and isFocused())
                screen.ok()
                await(hasTestTag("mini-guide"), false)
                await(hasTestTag("fullscreen-test-host") and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, channels[1].id))
                await(hasTestTag("fullscreen-banner"))
                shot(if (engine == "Media3") "fullscreen-banner" else "fullscreen-banner-vlc")
                screen.back()
                await(hasTestTag("fullscreen-banner"), false)
                // A second OK while the banner is visible opens the guide; Back dismisses it.
                screen.ok()
                await(hasTestTag("fullscreen-banner"))
                screen.ok()
                await(hasTestTag("mini-guide"))
                screen.back()
                await(hasTestTag("mini-guide"), false)
                // Deadlines use wall time and remain observable while Compose is idle.
                screen.ok()
                await(hasTestTag("fullscreen-banner"))
                await(hasTestTag("fullscreen-banner"), false)
                screen.dpad(Key.DirectionLeft)
                await(hasTestTag("mini-guide"))
                await(hasTestTag("mini-guide"), false)
                await(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                screen.dpad(Key.Menu)
                await(hasText("Yayına dön"))
                screen.back()
                await(hasText("Yayına dön"), false)
                screen.back()
                await(hasTestTag("fullscreen-test-host") and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Kapandı"))
            } finally {
                screen.runOnUiThread { screen.activity.setContent {} }
                runBlocking {
                    prefs.setShowAdult(previousAdult)
                    prefs.set("engine", previousEngine)
                    prefs.set("fallback", previousFallback)
                    prefs.set("recent_live", previousRecent)
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                }
            }
        }
    }
}
