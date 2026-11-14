package dev.streamora.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class RemoteNavigationTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test
    fun setupBackAndSettingsSourceNavigation() {
        fun waitForText(text: String) {
            screen.waitUntil(30_000) {
                screen.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun waitForSection(section: String) {
            screen.waitUntil(30_000) {
                screen.onAllNodesWithTag("section-$section").fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun waitForFocus(matcher: SemanticsMatcher) {
            try {
                screen.waitUntil(30_000) {
                    screen.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("Beklenen odak: $matcher\n" + screen.onRoot().printToString(), e)
            }
        }
        waitForText("✓ M3U")
        screen.back()
        waitForText("Kaynak ekle")
        screen.onNodeWithContentDescription("Ayarlar")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        waitForFocus(hasContentDescription("Ayarlar"))
        screen.onNodeWithTag("nav-open").assertExists()
        screen.ok()
        waitForSection("Ayarlar")
        screen.dpad(Key.DirectionRight)
        waitForFocus(hasText("IPTV Kaynakları"))
        screen.onNodeWithTag("nav-closed").assertExists()
        screen.onNodeWithText("IPTV Kaynakları").assertIsFocused()
        screen.ok()
        waitForText("✓ M3U")
        screen.back()
        waitForSection("Ayarlar")
        waitForFocus(hasText("IPTV Kaynakları"))
        screen.back()
        waitForText("Kaynak ekle")
        screen.onNodeWithContentDescription("Ara")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        waitForFocus(hasContentDescription("Ara"))
        screen.back()
        waitForFocus(hasText("Kaynak ekle"))
        screen.onNodeWithTag("nav-closed").assertExists()
        screen.onNodeWithText("Kaynak ekle").assertIsFocused()
    }
}
