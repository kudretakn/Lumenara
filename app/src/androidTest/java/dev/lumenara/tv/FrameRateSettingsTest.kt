package dev.lumenara.tv

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.catalog.FRAME_RATE_DEFAULT
import dev.lumenara.data.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FrameRateSettingsTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun frameRateChoiceCyclesAndPersists() {
        val prefs = Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val previous = runBlocking { prefs.text("frame_rate", FRAME_RATE_DEFAULT).first() }
        try {
            runBlocking { prefs.set("frame_rate", FRAME_RATE_DEFAULT) }
            screen.waitUntil(30_000) {
                screen.onAllNodesWithTag("section-IPTV Kaynakları").fetchSemanticsNodes().isNotEmpty() ||
                    screen.onAllNodesWithTag("hub").fetchSemanticsNodes().isNotEmpty()
            }
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
            }
        } finally {
            runBlocking { prefs.set("frame_rate", previous) }
        }
    }
}
