package dev.streamora.tv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.rules.TestRule

/** Send remote keys directly to Compose, independent of system window focus. */
fun ComposeContentTestRule.dpad(key: Key) {
    onRoot().performKeyInput { pressKey(key) }
    waitForIdle()
}

fun ComposeContentTestRule.ok() = dpad(Key.DirectionCenter)

fun ComposeContentTestRule.longOk() {
    onRoot().performKeyInput {
        keyDown(Key.DirectionCenter)
        // Advance the native event timestamp too: long OK uses eventTime - downTime.
        advanceEventTime(700)
        keyUp(Key.DirectionCenter)
    }
    waitForIdle()
}

fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.back() {
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}
