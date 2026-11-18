package dev.lumenara.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogGridColumnsTest {
    @Test fun mixedSpansKeepTheirColumnsEvenWhenItemPagesAreAbsent() {
        val pages = CatalogPages("", "", "", "", "", 80, 8)
        // The channel cannot fit after seven posters; it starts the next row.
        pages.slotKinds(List(7) { "MOVIE" } + listOf("LIVE", "SERIES", "LIVE", "MOVIE", "LIVE", "SERIES"))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 0, 2, 3, 5, 6, 0),
            (0..12).map(pages::columnOf))
        assertEquals(true, pages.pages.isEmpty())
    }

    @Test fun uniformCatalogsWrapAtTheGridWidth() {
        val posters = CatalogPages("", "MOVIE", "", "", "", 80, 8)
        val channels = CatalogPages("", "LIVE", "", "", "", 80, 8)
        assertEquals(listOf(6, 7, 0, 1), (398..401).map(posters::columnOf))
        assertEquals(listOf(4, 6, 0, 2), (398..401).map(channels::columnOf))
    }

    @Test fun returningFromAnEvictedWindowKeepsEveryColumnUntilTheFirstRow() {
        for (columns in listOf(4, 8)) {
            for (column in 0 until columns) {
                var index = 50 * columns + column
                for (row in 49 downTo 0) {
                    index = checkNotNull(previousGridRow(index, { it % columns }, { 1 }))
                    assertEquals(row * columns + column, index)
                }
                assertNull(previousGridRow(index, { it % columns }, { 1 }))
            }
        }
    }

    @Test fun previousRowAcceptsAnOverlappingChannelOrTheNearestCellInAShortRow() {
        val kinds = List(7) { "MOVIE" } + listOf("LIVE") + List(6) { "MOVIE" }
        val pages = CatalogPages("", "", "", "", "", 80, 8)
        pages.slotKinds(kinds)
        val span: (Int) -> Int = { if (pages.isLiveSlot(it)) 2 else 1 }
        assertEquals(0, previousGridRow(7, pages::columnOf, span))
        assertEquals(6, previousGridRow(13, pages::columnOf, span))
    }
}
