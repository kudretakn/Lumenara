package dev.lumenara.tv

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule

/** Send remote keys directly to Compose, independent of system window focus. */
fun ComposeContentTestRule.dpad(key: Key) {
    onRoot().performKeyInput { pressKey(key) }
    waitForIdle()
}

fun ComposeContentTestRule.ok() = dpad(Key.DirectionCenter)

/** TV Material Surface requires the repeated native KeyDown sent by a held remote key. */
fun ComposeContentTestRule.longPressOkNative() {
    val output = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("input keyevent --longpress 23")
    ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    waitForIdle()
}

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

/** Return through the real Back stack, then activate a Hub card with remote OK. */
fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.returnToHub() {
    repeat(10) {
        if (onAllNodesWithTag("hub").fetchSemanticsNodes().isNotEmpty()) return
        back()
    }
    onNodeWithTag("hub").assertExists()
}

fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.openHubCard(name: String) {
    returnToHub()
    onNodeWithTag("hub").performScrollToNode(hasTestTag("hub-$name"))
    val card = onNodeWithTag("hub-$name")
    card.performSemanticsAction(SemanticsActions.RequestFocus)
    waitUntil(30_000) { onAllNodes(hasTestTag("hub-$name") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    ok()
    waitUntil(30_000) { onAllNodesWithTag("section-$name").fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeContentTestRule.openCategory(name: String) {
    waitUntil(30_000) {
        runCatching {
            onNodeWithTag("category-grid").performScrollToNode(hasTestTag("category-$name"))
            onAllNodesWithTag("category-$name").fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }
    onNodeWithTag("category-$name").performSemanticsAction(SemanticsActions.RequestFocus)
    waitUntil(30_000) { onAllNodes(hasTestTag("category-$name") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    ok()
    waitUntil(30_000) { onAllNodesWithTag("category-grid").fetchSemanticsNodes().isEmpty() }
}
