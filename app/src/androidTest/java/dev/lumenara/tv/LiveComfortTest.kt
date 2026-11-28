package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.lumenara.data.*
import dev.lumenara.design.LumenaraTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiveComfortTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun media3ReconnectsAndSupportsNumberAndPreviousChannel() = journey("Media3")
    @Test fun vlcReconnectsAndSupportsNumberAndPreviousChannel() = journey("VLC")

    private fun await(matcher: SemanticsMatcher, present: Boolean = true) {
        try {
            screen.waitUntil(30_000) { screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() == present }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "Beklenen: $matcher, present=$present\n" + screen.onRoot().printToString() +
                    "\ncurrentFocus=${screen.runOnUiThread { screen.activity.currentFocus?.javaClass?.name }}", e
            )
        }
    }

    private fun journey(engine: String) {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val name = "Test · U1 $engine"
        val saved = runBlocking {
            listOf("engine", "fallback", "recent_live").associateWith { prefs.text(it).first() }
        }
        val bytes = test.context.assets.open("lumenara-test.ts").use { it.readBytes() }
        val requests = AtomicInteger()
        fun shot(filename: String) {
            val file = File(context.getExternalFilesDir(null), "screens/$filename.png")
            checkNotNull(file.parentFile).mkdirs()
            assertTrue(UiDevice.getInstance(test).takeScreenshot(file))
        }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/list") return MockResponse().setBody(buildString {
                        appendLine("#EXTM3U")
                        repeat(2) {
                            // Room has no number field: runtime uses Tümü position, not 42/43.
                            appendLine("#EXTINF:-1 tvg-chno=\"${42 + it}\" group-title=\"Test\",U1 Kanal ${it + 1}")
                            appendLine(server.url("/live/$it.ts"))
                        }
                    })
                    if (request.path?.endsWith(".ts") == true) {
                        if (requests.incrementAndGet() <= 2) return MockResponse().setResponseCode(503)
                        return MockResponse().setHeader("Content-Type", "video/mp2t")
                            .setBody(Buffer().write(bytes))
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start()
            try {
                val channels = runBlocking {
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                    prefs.set("engine", engine)
                    prefs.set("fallback", "Kapalı")
                    prefs.set("recent_live", "")
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                    val source = repo.library.snapshot().single { it.name == name }
                    repo.livePage(source.id, "", "", "", 80, 0).first()
                }
                screen.runOnUiThread {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        LumenaraTheme {
                            var selected by remember { mutableStateOf(channels.first()) }
                            Box(Modifier.fillMaxSize().testTag("u1-host").semantics { stateDescription = selected.id }) {
                                Playback(model, selected, channels, { selected = it }, {}, {})
                            }
                        }
                    }
                }
                await(hasTestTag("live-reconnecting"))
                // The counter text is short-lived on a fast reconnect; its format is covered by JVM tests.
                await(hasText("Yeniden bağlanıyor…", substring = true))
                shot("u1-reconnecting-$engine")
                screen.waitUntil(30_000) { runBlocking { prefs.recentLive.first().firstOrNull() == channels[0].id } }
                await(hasTestTag("live-reconnecting"), false)
                assertTrue(requests.get() >= 3)
                screen.wp21AwaitLiveFrame(channels[0].id)
                var originalView: androidx.media3.ui.PlayerView? = null
                var originalPlayer: androidx.media3.common.Player? = null
                if (engine == "Media3") screen.runOnIdle {
                    originalView = wp21FindPlayerView(screen.activity.window.decorView)
                    originalPlayer = originalView?.player
                    assertNotNull(originalPlayer)
                }
                screen.dpad(Key.Two)
                await(hasText("2") and hasAnyAncestor(hasTestTag("live-number-entry")))
                // No OK: the last digit must commit automatically after 1.5 seconds.
                await(hasTestTag("u1-host") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, channels[1].id))
                await(hasTestTag("live-number-entry"), false)
                await(hasTestTag("fullscreen-banner"))
                screen.onNode(hasText("2") and hasAnyAncestor(hasTestTag("fullscreen-banner"))).assertExists()
                screen.waitUntil(30_000) { runBlocking { prefs.recentLive.first().take(2) == listOf(channels[1].id, channels[0].id) } }
                screen.wp21AwaitLiveFrame(channels[1].id)
                if (engine == "Media3") screen.runOnIdle {
                    val currentView = wp21FindPlayerView(screen.activity.window.decorView)
                    assertSame("Channel switches keep the video view", originalView, currentView)
                    assertSame("Channel switches reuse ExoPlayer", originalPlayer, currentView?.player)
                }
                shot("u1-channel-two-$engine")
                screen.dpad(Key.Zero)
                await(hasTestTag("u1-host") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, channels[0].id))
                screen.wp21AwaitLiveFrame(channels[0].id)
                screen.dpad(Key.Nine)
                screen.ok()
                await(hasText("Kanal yok"))
                await(hasText("Kanal yok"), false)
                screen.onNodeWithTag("u1-host").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, channels[0].id))
                screen.waitUntil(30_000) { runBlocking { prefs.recentLive.first().firstOrNull() == channels[0].id } }
                screen.dpad(Key.LastChannel)
                await(hasTestTag("u1-host") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, channels[1].id))
                screen.waitUntil(30_000) { runBlocking { prefs.recentLive.first().firstOrNull() == channels[1].id } }

                screen.wp21AwaitLiveFrame(channels[1].id)

                // Hub starts on Canlı TV; recent channels still play directly from their shelf.
                screen.runOnUiThread {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        LumenaraTheme {
                            var playing by remember { mutableStateOf<Item?>(null) }
                            val current = playing
                            if (current != null) Playback(model, current, channels, { playing = it }, {}, {})
                            else Home(model, channels[0].source, {}, { item, _ -> playing = item }) {}
                        }
                    }
                }
                await(hasTestTag("hub-Canlı TV") and isFocused())
                val recentCard = hasContentDescription(channels[1].title) and
                    hasAnyAncestor(hasTestTag("channel-shelf-Son kanal"))
                screen.wp21ScrollTo("hub", recentCard)
                screen.wp21Focus(recentCard)
                shot("u1-last-channel-$engine")
                screen.ok()
                screen.wp21AwaitLiveFrame(channels[1].id)

                // The same input also works on the Live TV page, without depending on a filtered row index.
                screen.runOnUiThread {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        LumenaraTheme {
                            var playing by remember { mutableStateOf<Item?>(null) }
                            Box(Modifier.fillMaxSize().testTag("u1-live-host").semantics { stateDescription = playing?.id.orEmpty() }) {
                                LiveScreen(model, channels[0].source, {}, { item, _ -> playing = item })
                            }
                        }
                    }
                }
                screen.openCategory("Tüm kanallar")
                await(hasTestTag("live-channel-${channels[0].id}"))
                screen.wp21Focus(hasTestTag("live-channel-${channels[0].id}"))
                screen.dpad(Key.NumPad2)
                await(hasText("2") and hasAnyAncestor(hasTestTag("live-number-entry")))
                screen.ok()
                await(hasTestTag("u1-live-host") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, channels[1].id))
            } finally {
                screen.runOnUiThread { screen.activity.setContent {} }
                runBlocking {
                    saved.forEach { (key, value) -> prefs.set(key, value) }
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                }
            }
        }
    }
}
