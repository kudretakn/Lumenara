package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import dev.lumenara.design.LumenaraTheme
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProgramDiscoveryTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun await(matcher: SemanticsMatcher) {
        screen.waitUntil(30_000) { screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun searchProgrammePlaysFullscreenAndAiringSortsByRemainingTime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val saved = runBlocking { listOf("engine", "fallback", "recent_live").associateWith { prefs.text(it).first() } }
        val name = "Test · U2 Program discovery"
        val now = System.currentTimeMillis()
        val time = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val bytes = instrumentation.context.assets.open("lumenara-test.ts").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/list" -> MockResponse().setBody("""#EXTM3U
                        |#EXTINF:-1 tvg-id="long" group-title="Test",A Uzun
                        |${server.url("/live.ts")}
                        |#EXTINF:-1 tvg-id="short" group-title="Test",Z Kısa
                        |${server.url("/short.ts")}
                    """.trimMargin())
                    "/guide" -> MockResponse().setBody("""<tv>
                        <programme channel="long" start="${time.format(Instant.ofEpochMilli(now - 60_000))}" stop="${time.format(Instant.ofEpochMilli(now + 3_600_000))}"><title>U2 Özel Yayın</title></programme>
                        <programme channel="short" start="${time.format(Instant.ofEpochMilli(now - 60_000))}" stop="${time.format(Instant.ofEpochMilli(now + 1_800_000))}"><title>Kısa Program</title></programme>
                        </tv>""")
                    "/live.ts", "/short.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(bytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            try {
                val channels = runBlocking {
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                    prefs.set("engine", "Media3")
                    prefs.set("fallback", "Kapalı")
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                    val source = repo.library.snapshot().single { it.name == name }
                    repo.addGuide(source.id, server.url("/guide").toString())
                    repo.library.browse(source.id, "LIVE", "", "", "", 80).first()
                }
                val long = channels.single { it.tvgId == "long" }
                val short = channels.single { it.tvgId == "short" }
                val playing = mutableStateOf<Item?>(null)
                val page = mutableStateOf("Ara")
                screen.runOnUiThread {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        LumenaraTheme {
                            val current = playing.value
                            if (current != null) Playback(model, current, channels, { playing.value = it }, { playing.value = null }, {})
                            else Column {
                                Box(Modifier.weight(1f)) {
                                    if (page.value == "Ara") SearchScreen(model, long.source, {}, { item, _ -> playing.value = item })
                                    else LiveScreen(model, long.source, {}, { item, _ -> playing.value = item })
                                }
                            }
                        }
                    }
                }
                await(hasSetTextAction())
                screen.onNode(hasSetTextAction()).performTextInput("özel yayın")
                await(hasTestTag("search-shelf-Programlar"))
                val hit = hasText("U2 Özel Yayın") and hasAnyAncestor(hasTestTag("search-shelf-Programlar"))
                screen.onNode(hit).assertExists()
                screen.onNode(hasText("Şimdi") and hasAnyAncestor(hasTestTag("search-shelf-Programlar"))).assertExists()
                // Focus the FocusCard itself, then use the physical TV OK path.
                screen.onNode(hasClickAction() and hasText("U2 Özel Yayın"))
                    .performSemanticsAction(SemanticsActions.RequestFocus)
                screen.ok()
                await(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                screen.waitUntil(30_000) { runBlocking { prefs.recentLive.first().firstOrNull() == long.id } }
                // Close playback in the test host, then select the Live TV page.
                screen.runOnIdle { playing.value = null }
                screen.runOnIdle { page.value = "Canlı TV" }
                screen.openCategory("Şu an yayında")
                await(hasTestTag("live-channel-${short.id}"))
                screen.waitUntil(30_000) {
                    val first = screen.onNodeWithTag("live-channel-${short.id}").fetchSemanticsNode().boundsInRoot.top
                    val second = screen.onNodeWithTag("live-channel-${long.id}").fetchSemanticsNode().boundsInRoot.top
                    val firstLeft = screen.onNodeWithTag("live-channel-${short.id}").fetchSemanticsNode().boundsInRoot.left
                    val secondLeft = screen.onNodeWithTag("live-channel-${long.id}").fetchSemanticsNode().boundsInRoot.left
                    first < second || first == second && firstLeft < secondLeft
                }
                screen.onNode(hasSetTextAction()).performTextInput("uzun")
                screen.waitUntil(30_000) { screen.onAllNodesWithTag("live-channel-${short.id}").fetchSemanticsNodes().isEmpty() }
                screen.onNodeWithTag("live-channel-${long.id}").assertExists()
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
