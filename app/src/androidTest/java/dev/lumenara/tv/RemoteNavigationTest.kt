package dev.lumenara.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Repository
import dev.lumenara.data.SourceSpec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteNavigationTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun setupBackAndSettingsSourceNavigation() {
        fun waitFor(matcher: SemanticsMatcher) {
            try {
                screen.waitUntil(30_000) { screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) {
                screen.onRoot().printToLog("HUB")
                throw failure
            }
        }
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = "Test · Kumanda"
        MockWebServer().use { server ->
            server.start()
            try {
                runBlocking {
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                    server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1 group-title=\"Haber\",Kumanda Haber\n${server.url("/live.ts")}\n"))
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                }
                try {
                    screen.waitUntil(30_000) {
                        runCatching {
                            screen.onNodeWithTag("source-setup").performScrollToNode(hasText(name))
                            screen.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
                        }.getOrDefault(false)
                    }
                } catch (failure: Throwable) {
                    screen.onRoot().printToLog("HUB")
                    throw failure
                }
                screen.back()
                waitFor(hasTestTag("hub-Canlı TV") and isFocused())
                screen.onNodeWithTag("hub-Canlı TV").assertIsFocused()
                screen.openHubCard("Ayarlar")
                waitFor(hasText("IPTV Kaynakları") and isFocused())
                screen.ok()
                waitFor(hasText("Liste adresi gir"))
                screen.back()
                waitFor(hasText("IPTV Kaynakları") and isFocused())
                screen.back()
                waitFor(hasTestTag("hub-Ayarlar") and isFocused())
                screen.onNodeWithTag("hub-Ayarlar").assertIsFocused()
                screen.dpad(Key.DirectionLeft)
                waitFor(hasTestTag("hub-Ara") and isFocused())
                screen.ok()
                waitFor(hasTestTag("section-Ara"))
                screen.back()
                waitFor(hasTestTag("hub-Ara") and isFocused())
            } finally {
                runBlocking { repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) } }
            }
        }
    }
    @Test fun emptySetupAlsoRequiresTwoBackPresses() {
        screen.waitUntil(30_000) { screen.onAllNodesWithText("Liste adresi gir").fetchSemanticsNodes().isNotEmpty() }
        val activity = screen.activity
        screen.back()
        screen.waitUntil(30_000) { screen.onAllNodesWithTag("exit-confirmation").fetchSemanticsNodes().isNotEmpty() }
        screen.onNodeWithText("Liste adresi gir").assertExists()
        assertFalse(activity.isFinishing)
        screen.waitUntil(30_000) { screen.onAllNodesWithTag("exit-confirmation").fetchSemanticsNodes().isEmpty() }
        screen.back()
        screen.waitUntil(30_000) { screen.onAllNodesWithTag("exit-confirmation").fetchSemanticsNodes().isNotEmpty() }
        screen.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        screen.waitUntil(30_000) { activity.isFinishing || activity.isDestroyed }
    }

}
