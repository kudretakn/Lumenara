package dev.lumenara.design

import androidx.compose.foundation.ExperimentalFoundationApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class FocusScrollingTest {
    @Test fun visibleTargetsKeepTheirOffsetIncludingBothEdges() {
        for (offset in listOf(0f, 0.25f, 240f, 420f)) {
            assertEquals(0f, StableFocusBringIntoViewSpec.calculateScrollDistance(offset, 60f, 480f), 0f)
        }
    }

    @Test fun crossingAnEdgeScrollsOnlyEnoughToRevealTheNextRow() {
        assertEquals(10f, StableFocusBringIntoViewSpec.calculateScrollDistance(430f, 60f, 480f), 0f)
        assertEquals(-10f, StableFocusBringIntoViewSpec.calculateScrollDistance(-10f, 60f, 480f), 0f)
        // The resulting row is stationary on subsequent horizontal focus changes.
        assertEquals(0f, StableFocusBringIntoViewSpec.calculateScrollDistance(420f, 60f, 480f), 0f)
    }

    @Test fun oversizedShelfDoesNotOscillateBetweenEdges() {
        assertEquals(0f, StableFocusBringIntoViewSpec.calculateScrollDistance(-10f, 500f, 480f), 0f)
    }
}
