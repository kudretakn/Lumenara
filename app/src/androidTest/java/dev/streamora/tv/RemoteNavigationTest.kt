package dev.streamora.tv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test

class RemoteNavigationTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test
    fun setupBackAndSettingsSourceNavigation() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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
            screen.waitUntil(30_000) {
                screen.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
        }
        waitForText("✓ M3U")
        device.pressBack()
        waitForText("Kaynak ekle")
        screen.onNodeWithContentDescription("Ayarlar")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        waitForFocus(hasContentDescription("Ayarlar"))
        screen.onNodeWithTag("nav-open").assertExists()
        device.pressDPadCenter()
        waitForSection("Ayarlar")
        device.pressDPadRight()
        waitForFocus(hasText("IPTV Kaynakları"))
        screen.onNodeWithTag("nav-closed").assertExists()
        screen.onNodeWithText("IPTV Kaynakları").assertIsFocused()
        device.pressDPadCenter()
        waitForText("✓ M3U")
        device.pressBack()
        waitForSection("Ayarlar")
        waitForFocus(hasText("IPTV Kaynakları"))
        device.pressBack()
        waitForText("Kaynak ekle")
        screen.onNodeWithContentDescription("Ara")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        waitForFocus(hasContentDescription("Ara"))
        device.pressBack()
        waitForFocus(hasText("Kaynak ekle"))
        screen.onNodeWithTag("nav-closed").assertExists()
        screen.onNodeWithText("Kaynak ekle").assertIsFocused()
    }
}
