package dev.lumenara.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchQueryTest {
    @Test fun sixCharactersInOneSecondStartOnlyOneQueryGroup() = runTest {
        val input = MutableStateFlow("")
        val groups = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            input.debouncedSearchQuery().collect { if (it.isNotEmpty()) groups += it }
        }
        "Kanalı".indices.forEach { index ->
            input.value = "Kanalı".take(index + 1)
            advanceTimeBy(150)
        }
        assertTrue(groups.isEmpty())
        advanceTimeBy(149)
        runCurrent()
        assertTrue(groups.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("kanali"), groups)
    }

    @Test fun shortInputClearsImmediatelyAndNormalisedDuplicatesDoNotSearchAgain() = runTest {
        val input = MutableStateFlow("İĞ")
        val queries = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            input.debouncedSearchQuery().collect { queries += it }
        }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("ig"), queries)
        input.value = " ig "
        advanceTimeBy(500)
        assertEquals(listOf("ig"), queries)
        input.value = "i"
        runCurrent()
        assertEquals(listOf("ig", ""), queries)
        input.value = "ab"
        advanceTimeBy(299)
        input.value = ""
        advanceTimeBy(500)
        assertEquals(listOf("ig", ""), queries)
    }
}
