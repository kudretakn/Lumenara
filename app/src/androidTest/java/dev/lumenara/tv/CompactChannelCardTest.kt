package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.lumenara.data.Item
import dev.lumenara.data.Slot
import dev.lumenara.design.LumenaraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompactChannelCardTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun focusedPlaceholderNotifiesWhenItsPageArrives() {
        val row = Item("arrived", "absent-source", "Arrived channel", "", "LIVE", "", "", "", 0, "invalid-ciphertext")
        val item = mutableStateOf<Item?>(null)
        var calls = 0
        screen.runOnUiThread {
            val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
            screen.activity.setContent {
                LumenaraTheme {
                    ChannelCard(model, item.value, 0, {}, { calls++ }, compact = true, compactLogoSize = 28)
                }
            }
        }
        screen.onNodeWithTag("live-channel-").performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        screen.runOnIdle { assertEquals(0, calls); item.value = row }
        screen.onNodeWithTag("live-channel-arrived").assertIsFocused()
        screen.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun focusNotifiesOnceWithoutRecomposingParentAndOperationsRestoreTheCard() {
        val rows = List(4) { index ->
            // Compact cards must use the supplied logo, never decrypt this field.
            Item("compact-$index", "absent-source", "Channel $index", "", "LIVE", "", "", "", 0, "invalid-ciphertext")
        }
        val focusCalls = IntArray(rows.size)
        var compositions = 0
        val now = System.currentTimeMillis()
        val programme = Slot("feed", "channel", now - 60_000, now + 60_000, "Now playing", "", "")
        screen.runOnUiThread {
            val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
            screen.activity.setContent {
                LumenaraTheme {
                    var showing by remember { mutableStateOf(true) }
                    val saved = rememberSaveableStateHolder()
                    if (showing) saved.SaveableStateProvider("cards") {
                        val last = rememberSaveable { mutableStateOf<String?>(null) }
                        SideEffect { compositions++ }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rows.forEachIndexed { index, item ->
                                ChannelCard(model, item, now, {}, {
                                    focusCalls[index]++
                                    last.value = it.id
                                }, Modifier.width(200.dp), compact = true,
                                    currentProgramme = if (index == 1) programme else null,
                                    compactLogoSize = 28, operations = { showing = false })
                            }
                        }
                    } else Button(onClick = { showing = true }) { Text("Return") }
                }
            }
        }
        fun card(index: Int) = screen.onNodeWithTag("live-channel-${rows[index].id}")
        card(0).performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        var before = 0
        screen.runOnIdle {
            before = compositions
            focusCalls.fill(0)
        }
        screen.dpad(Key.DirectionRight)
        card(1).assertIsFocused()
        screen.onNodeWithText("Now playing", useUnmergedTree = true).assertIsDisplayed()
        screen.runOnIdle {
            assertArrayEquals(intArrayOf(0, 1, 0, 0), focusCalls)
            assertEquals("Focus must not invalidate the parent", before, compositions)
        }
        card(1).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        screen.onNodeWithText("Return").performSemanticsAction(SemanticsActions.RequestFocus)
        screen.ok()
        screen.waitUntil(10_000) {
            screen.onAllNodes(hasTestTag("live-channel-${rows[1].id}") and isFocused())
                .fetchSemanticsNodes().isNotEmpty()
        }
        screen.dpad(Key.DirectionLeft)
        card(0).assertIsFocused()
    }
}
