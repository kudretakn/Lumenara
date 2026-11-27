package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.lumenara.data.Item
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WindowPagesDriverTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun advancingLoadsOnlyMissingPagesAndRetainedPagesStillObserveUpdates() {
        val size = LIVE_PAGE_SIZE
        val rows = List(9 * size) { index ->
            Item("$index", "test", "Channel $index", "", "LIVE", "", "", "", 0, "")
        }
        val updates = List(9) { page -> MutableStateFlow(rows.subList(page * size, (page + 1) * size)) }
        val starts = IntArray(9)
        val stops = IntArray(9)
        val nextPage = CompletableDeferred<Unit>()
        val state = WindowPages(size, LIVE_GRID_COLUMNS)
        screen.runOnUiThread {
            screen.activity.setContent {
                rememberWindowPagesDriver(state, page = { first, last ->
                    assertEquals("Load one missing page at a time", first, last)
                    flow {
                        starts[first]++
                        try {
                            if (first == 5) nextPage.await()
                            emitAll(updates[first])
                        } finally { stops[first]++ }
                    }
                }, total = { flowOf(rows.size) }, index = { 2 * size })
            }
        }
        // SnapshotStateMap's key view uses identity equality; compare a value snapshot.
        screen.waitUntil(10_000) { state.pages.keys.toSet() == (0..4).toSet() }
        var retained: List<Item>? = null
        screen.runOnIdle {
            assertArrayEquals(intArrayOf(1, 1, 1, 1, 1, 0, 0, 0, 0), starts)
            retained = state.pages[2]
            state.focused(3 * size)
        }
        screen.waitUntil(10_000) { starts[5] == 1 && stops[0] == 1 }
        screen.runOnIdle {
            assertSame("Existing cards remain usable while the next page waits", retained, state.pages[2])
            assertEquals((1..4).toSet(), state.pages.keys.toSet())
            nextPage.complete(Unit)
        }
        screen.waitUntil(10_000) { state.pages.keys.toSet() == (1..5).toSet() }
        screen.runOnIdle {
            assertArrayEquals(intArrayOf(1, 1, 1, 1, 1, 1, 0, 0, 0), starts)
            assertSame("Overlap retains the original Items", retained, state.pages[2])
            val changed = updates[2].value.toMutableList()
            changed[0] = changed[0].copy(title = "Updated while retained")
            updates[2].value = changed
        }
        screen.waitUntil(10_000) { state[2 * size]?.title == "Updated while retained" }
        screen.runOnIdle { state.focused(2 * size) }
        screen.waitUntil(10_000) { state.pages.keys.toSet() == (0..4).toSet() && stops[5] == 1 }
        screen.runOnIdle {
            assertArrayEquals(intArrayOf(2, 1, 1, 1, 1, 1, 0, 0, 0), starts)
            assertEquals(rows[0], state[0])
            assertEquals("Updated while retained", state[2 * size]?.title)
            assertTrue(state.pages.values.sumOf { it.size } <= 5 * size)
        }
    }

}

class PagingWindowRetentionTest {
    @Test fun installingOnePagePreservesOverlapAndShrinkDropsStaleSlots() {
        val window = PagingWindow<Int>(4)
        window.install(0..4, (0 until 20).toList())
        val retained = window.pages[2]
        window.retain(1..5)
        window.installPage(5, listOf(20, 21))
        assertSame(retained, window.pages[2])
        window.installPage(2, listOf(8, 9, 10, 11))
        assertSame("Equal invalidation preserves identity", retained, window.pages[2])
        assertNull(window[0])
        assertEquals(21, window[21])
        assertNull(window[22])
        window.retain(1..2)
        val input = mutableListOf(100)
        window.installPage(2, input)
        input[0] = -1
        assertEquals(100, window[8])
        assertNull(window[9])
        assertEquals(setOf(1, 2), window.pages.keys)
    }
}
