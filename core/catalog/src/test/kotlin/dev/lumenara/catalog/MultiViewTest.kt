package dev.lumenara.catalog

import org.junit.Assert.*
import org.junit.Test

class MultiViewTest {
    @Test
    fun enforcesBothConnectionAndDecoderLimits() {
        val inputs = listOf("A1", "A2", "B1", "B2")
        fun choose(count: Int, slots: Int) =
            multiSelection(count, slots, inputs, mapOf("A" to 1, "B" to 2)) { it.take(1) }
        assertEquals(listOf("A1", "B1"), choose(2, 2).entries)
        assertTrue(choose(4, 4).entries.isEmpty())
        assertTrue(choose(2, 1).entries.isEmpty())
    }
}
