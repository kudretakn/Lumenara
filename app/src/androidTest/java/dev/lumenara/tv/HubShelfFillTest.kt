package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.lumenara.data.Item
import dev.lumenara.design.FocusCard
import dev.lumenara.design.LumenaraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HubShelfFillTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val rows = List(24) { index ->
        Item("fill-$index", "fill", "Film $index", "Film", "MOVIE", "", "", "film $index", 1, "")
    }

    private fun showShelf(firstDraw: AtomicInteger, filled: AtomicBoolean = AtomicBoolean()) {
        screen.runOnUiThread {
            screen.activity.setContent {
                LumenaraTheme {
                    val drawn = remember { mutableSetOf<String>() }
                    val anchor = remember { FocusRequester() }
                    LaunchedEffect(Unit) { anchor.requestFocus() }
                    // Exactly four full slots, including the row's 8 dp side padding.
                    Column(Modifier.width(646.dp).drawWithContent {
                        drawn.clear()
                        drawContent()
                        firstDraw.compareAndSet(-1, drawn.size)
                    }) {
                        FocusCard({}, Modifier.size(1.dp).focusRequester(anchor)) {}
                        HubShelf("Films", "fill-shelf", rows, 150.dp, onFilled = { filled.set(true) }) { item ->
                            FocusCard({}, Modifier.fillMaxWidth().height(84.dp)
                                .testTag(item.id).drawWithContent {
                                    drawn += item.id
                                    drawContent()
                                }) {}
                        }
                        Box(Modifier.size(1.dp).testTag("after-shelf"))
                    }
                }
            }
        }
        screen.waitForIdle()
    }

    @Test fun firstDrawKeepsOneTargetAndFillsTheViewportAcrossFramesWithoutMovingIt() {
        screen.mainClock.autoAdvance = false
        try {
            val firstDraw = AtomicInteger(-1)
            val filled = AtomicBoolean()
            showShelf(firstDraw, filled)
            assertEquals("Cold traversal must not build every visible TV card", 1, firstDraw.get())
            assertFalse("Sized placeholders must not count as a filled shelf", filled.get())
            val before = screen.onNodeWithTag("after-shelf").getUnclippedBoundsInRoot()
            var previous = screen.onAllNodes(hasTestTag("fill-0") or hasTestTag("fill-1") or
                hasTestTag("fill-2") or hasTestTag("fill-3")).fetchSemanticsNodes().size
            repeat(10) {
                screen.mainClock.advanceTimeByFrame()
                screen.waitForIdle()
                val count = screen.onAllNodes(hasTestTag("fill-0") or hasTestTag("fill-1") or
                    hasTestTag("fill-2") or hasTestTag("fill-3")).fetchSemanticsNodes().size
                assertTrue("At most one initial tile may be admitted per frame", count <= previous + 1)
                assertEquals("Filling must not move the next shelf", before,
                    screen.onNodeWithTag("after-shelf").getUnclippedBoundsInRoot())
                previous = count
            }
            assertEquals(4, previous)
            assertTrue("The full-Hub timing must be released after the visible tiles fill", filled.get())
            screen.onNodeWithTag("fill-23").assertDoesNotExist()
        } finally {
            screen.mainClock.autoAdvance = true
            screen.runOnUiThread { screen.activity.setContent {} }
        }
    }

    @Test fun enteringDuringFillKeepsFocusAndRemoteNavigationDoesNotSkipSlots() {
        screen.mainClock.autoAdvance = false
        try {
            showShelf(AtomicInteger(-1))
            screen.onNodeWithTag("fill-0").performSemanticsAction(SemanticsActions.RequestFocus)
            screen.mainClock.advanceTimeByFrame()
            screen.waitForIdle()
            screen.onNodeWithTag("fill-0").assertIsFocused()
            screen.mainClock.autoAdvance = true
            // Cross the initial viewport boundary too: focus search must compose
            // offscreen targets immediately, rather than wait for the fill timer.
            for (index in 1..6) {
                screen.dpad(Key.DirectionRight)
                screen.waitUntil(10_000) {
                    screen.onAllNodes(hasTestTag("fill-$index") and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
            }
        } finally {
            screen.mainClock.autoAdvance = true
            screen.runOnUiThread { screen.activity.setContent {} }
        }
    }
}
