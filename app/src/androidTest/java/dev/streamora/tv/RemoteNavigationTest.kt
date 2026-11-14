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
            screen.waitUntil(5000) {
                screen.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        waitForText("✓ M3U")
        device.pressBack()
        waitForText("Kaynak ekle")
        screen.onNodeWithContentDescription("Ayarlar")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        screen.waitForIdle()
        screen.onNodeWithTag("nav-open").assertExists()
        device.pressDPadCenter()
        waitForText("IPTV Kaynakları")
        device.pressDPadRight()
        screen.waitForIdle()
        screen.onNodeWithTag("nav-closed").assertExists()
        screen.onNodeWithText("IPTV Kaynakları").assertIsFocused()
        device.pressDPadCenter()
        waitForText("✓ M3U")
        device.pressBack()
        waitForText("IPTV Kaynakları")
        device.pressBack()
        waitForText("Kaynak ekle")
        screen.onNodeWithContentDescription("Ara")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        screen.waitForIdle()
        device.pressBack()
        screen.waitForIdle()
        screen.onNodeWithTag("nav-closed").assertExists()
        screen.onNodeWithText("Kaynak ekle").assertIsFocused()
    }
}
