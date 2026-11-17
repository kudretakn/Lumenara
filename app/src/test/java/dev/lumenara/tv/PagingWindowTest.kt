package dev.lumenara.tv

import org.junit.Assert.*
import org.junit.Test

class PagingWindowTest {
    @Test fun firstReadIsOnePageBeforeCountAndEdgesAreClamped() {
        val window = PagingWindow<Int>(80)
        assertEquals(0..0, window.range(0, 0, false))
        assertEquals(17..17, window.range(17, 0, false))
        assertEquals(0..2, window.range(0, 17_183, true))
        assertEquals(212..214, window.range(999, 17_183, true))
        assertEquals(0..0, window.range(0, 0, true))
    }

    @Test fun thousandsOfSlotsRetainOnlyFivePagesAndEvictedSlotsReloadAtSameIndex() {
        val window = PagingWindow<Int>(80)
        val total = 17_183
        fun load(center: Int) {
            val range = window.range(center, total, true)
            val first = range.first * window.pageSize
            val end = minOf((range.last + 1) * window.pageSize, total)
            window.install(range, (first until end).toList())
            assertTrue(window.pages.size <= 5)
            assertTrue(window.pages.values.sumOf { it.size } <= 400)
        }
        load(0)
        assertEquals(0, window[0])
        for (page in 1..200) {
            load(page)
            assertEquals(page * 80, window[page * 80])
        }
        assertNull("An early slot retains no Item", window[0])
        assertFalse(window.pages.containsKey(0))
        for (page in 199 downTo 0) {
            load(page)
            assertEquals(page * 80, window[page * 80])
        }
        assertEquals(0, window[0])
        assertNull(window[16_000])
    }

    @Test fun partialLastPageAndCatalogShrinkDiscardStaleItems() {
        val window = PagingWindow<Int>(28)
        window.install(0..2, (0 until 63).toList())
        assertEquals(62, window[62])
        assertNull(window[63])
        window.install(0..0, listOf(100, 101))
        assertEquals(setOf(0), window.pages.keys)
        assertEquals(101, window[1])
        assertNull(window[2])
        assertNull(window[-1])
    }

    @Test fun visibleRowsNeverPullTheWindowAwayFromNearbyFocusedRow() {
        val window = PagingWindow<Int>(80)
        assertEquals(2, window.visibleCenter(79, 160))
        assertEquals(2, window.visibleCenter(240, 160))
        assertEquals(20, window.visibleCenter(1_600, 160))
        assertEquals(3, window.visibleCenter(240, null))
    }

    @Test fun installedPagesDoNotRetainTheIncomingLargeList() {
        val window = PagingWindow<Int>(8)
        val input = (0 until 24).toMutableList()
        window.install(0..2, input)
        input[0] = -1
        assertEquals(0, window[0])
    }
}
