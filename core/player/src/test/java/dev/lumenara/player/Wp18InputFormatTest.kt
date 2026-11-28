package dev.lumenara.player

import org.junit.Assert.*
import org.junit.Test

class Wp18InputFormatTest {
    @Test fun selectedAbrChangesUpdateRateAndLatePreviousInputCannotOverwriteIt() {
        val selected = Wp18InputFormat("new-generation")
        assertFalse(selected.accept("old-generation", 60f))
        assertNull(selected.frameRate)
        assertTrue(selected.accept("new-generation", 23.976f))
        assertEquals(23.976f, selected.frameRate!!, .001f)
        assertTrue(selected.accept("new-generation", 50f))
        assertFalse(selected.accept("old-generation", 30f))
        assertEquals(50f, selected.frameRate!!, 0f)
        assertTrue(selected.accept("new-generation", -1f))
        assertNull(selected.frameRate)
        selected.accept("new-generation", Float.NaN)
        assertNull(selected.frameRate)
    }
}
