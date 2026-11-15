package dev.lumenara.tv

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.catalog.FRAME_RATE_DEFAULT
import dev.lumenara.data.Preferences
import dev.lumenara.data.Repository
import dev.lumenara.data.SourceSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class FrameRateSettingsTest {
    val screen = createAndroidComposeRule<MainActivity>()

    // Empty setup handles Back as double-Back exit, so it cannot be used to reach the Hub.
    // Seed before Activity launch, and clean up after its rule has disposed the UI.
    private val fixture = TestRule { test, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val repo = Repository.get(context)
                val prefs = Preferences(context)
                val name = "Test · Kare hızı · ${java.util.UUID.randomUUID()}"
                val previous = runBlocking { prefs.text("frame_rate", FRAME_RATE_DEFAULT).first() }
                MockWebServer().use { server ->
                    server.start()
                    try {
                        runBlocking {
                            prefs.set("frame_rate", FRAME_RATE_DEFAULT)
                            server.enqueue(MockResponse().setBody(
                                "#EXTM3U\n#EXTINF:-1,Kare hızı\n${server.url("/live.ts")}\n"
                            ))
                            repo.add(name, SourceSpec(server.url("/list").toString(), true))
                        }
                        test.evaluate()
                    } finally {
                        runBlocking {
                            try {
                                prefs.set("frame_rate", previous)
                            } finally {
                                repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                            }
                        }
                    }
                }
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(screen)

    @Test fun frameRateChoiceCyclesAndPersists() {
        val prefs = Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
        screen.waitUntil(30_000) { screen.onAllNodesWithTag("hub").fetchSemanticsNodes().isNotEmpty() }
        screen.openHubCard("Ayarlar")
        for (next in listOf("Her zaman", "Kapalı", FRAME_RATE_DEFAULT)) {
            screen.onNodeWithTag("settings-list").performScrollToNode(hasText("Kare hızını eşle", substring = true))
            val choice = hasText("Kare hızını eşle", substring = true)
            screen.onNode(choice).performSemanticsAction(SemanticsActions.RequestFocus)
            screen.waitUntil(5_000) { screen.onAllNodes(choice and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            screen.ok()
            screen.waitUntil(5_000) {
                screen.onAllNodesWithText("Kare hızını eşle · $next").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(next, runBlocking { prefs.text("frame_rate", FRAME_RATE_DEFAULT).first() })
            screen.activityRule.scenario.recreate()
            screen.onNodeWithTag("settings-list").performScrollToNode(choice)
            screen.waitUntil(5_000) {
                screen.onAllNodesWithText("Kare hızını eşle · $next").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(next, runBlocking { prefs.text("frame_rate", FRAME_RATE_DEFAULT).first() })
        }
    }
}
