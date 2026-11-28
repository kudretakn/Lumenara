package dev.lumenara.tv

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class Wp08SearchReturnTest {
    private val target = Wp08SearchReturn("source", "film", "Filmler", "item-59",
        59, 54, 37, 1, 19, listOf("Hidden"))

    @Test fun sameIdKeepsItsExactViewportAndOffset() {
        assertEquals(54 to 37, target.viewport(59, sameItem = true))
        assertEquals(53 to 37, target.viewport(58, sameItem = true))
    }

    @Test fun removedIdFallsBackToFirstSlotWithoutOldOffset() {
        assertEquals(0 to 0, target.viewport(0, sameItem = false))
    }

    @Test fun newQuerySourceOrVisibilityCannotUseTheOldReturnTarget() {
        assertTrue(target.matches("source", "film", listOf("Hidden")))
        assertFalse(target.matches("source", "other", listOf("Hidden")))
        assertFalse(target.matches("other", "film", listOf("Hidden")))
        assertFalse(target.matches("source", "film", emptyList()))
    }

    @Test fun navigationMetadataSurvivesSaveableScreenRemoval() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(Wp08SearchReturn.Saver) { scope.save(target) }
        assertEquals(target, Wp08SearchReturn.Saver.restore(checkNotNull(saved)))
    }
}
