package dev.lumenara.tv

import dev.lumenara.data.Wp19SpanWindow
import org.junit.Assert.*
import org.junit.Test

class Wp19CatalogSpansTest {
    @Test fun spanWindowsUseAbsoluteSlotsAndReplaceEvictedMetadata() {
        val pages = CatalogPages("", "", "", "", "", 80, 8)
        pages.installSpans(Wp19SpanWindow(398, listOf(false, true, false), listOf(6, 0, 2)))
        assertEquals(listOf(6, 0, 2), (398..400).map(pages::columnOf))
        assertFalse(pages.isLiveSlot(398))
        assertTrue(pages.isLiveSlot(399))
        pages.installSpans(Wp19SpanWindow(800, listOf(true, false), listOf(4, 6)))
        assertFalse(pages.isLiveSlot(399))
        assertEquals(0, pages.columnOf(398))
        assertTrue(pages.isLiveSlot(800))
        assertEquals(6, pages.columnOf(801))
        assertTrue(pages.pages.isEmpty())
    }
}
