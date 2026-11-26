package dev.lumenara.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CategoryFirstDrawTest {
    @get:Rule val screen = createComposeRule()

    @Test fun waitsForReadyContentDrawAndCompletesEmptyResultsOnce() {
        var now = 100L
        val lines = mutableListOf<String>()
        var contentDrawn = false
        val marks = PerfTransitions({ now }, {
            assertTrue("Completion must follow content drawing", contentDrawn)
            lines.add(it)
        })
        val ready = mutableStateOf(false)
        val redraw = mutableStateOf(0)
        marks.categoryStart()
        screen.setContent {
            Box(Modifier.size(40.dp).categoryFirstDraw(ready.value, marks).drawWithContent {
                redraw.value
                drawContent()
                if (ready.value) contentDrawn = true
            }) // An empty result still has a completed grid draw.
        }
        screen.runOnIdle { assertTrue(lines.isEmpty()); now = 250; ready.value = true }
        screen.waitForIdle()
        screen.runOnIdle {
            assertTrue(contentDrawn)
            assertEquals(listOf("category_grid_first_draw ms=150"), lines)
            redraw.value++
        }
        screen.waitForIdle()
        screen.runOnIdle { assertEquals(1, lines.size) }
    }
}
