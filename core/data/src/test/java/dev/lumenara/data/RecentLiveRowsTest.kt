package dev.lumenara.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentLiveRowsTest {
    @Test fun preservesSavedRecencyAndDropsMissingOrOtherSourceRows() {
        val ids = listOf("last", "deleted", "other-source", "older")
        val categories = linkedMapOf("older" to "Haber", "last" to "Spor")
        assertEquals(listOf("last", "older"), visibleRecentIds(ids, categories, false))
    }

    @Test fun adultToggleUsesTheSameCategoryRuleWithoutChangingOrder() {
        val ids = listOf("adult", "news", "xxx", "empty", "adult-number")
        val categories = mapOf("news" to "Haber", "adult" to "For Adults", "xxx" to "XXX",
            "empty" to "", "adult-number" to "Filmler +18")
        assertEquals(listOf("news", "empty"), visibleRecentIds(ids, categories, false))
        assertEquals(ids, visibleRecentIds(ids, categories, true))
        assertEquals(listOf("news", "empty"), visibleRecentIds(ids, categories, false))
    }

    @Test fun emptyHistoryAndEntirelyDeletedHistoryAreEmpty() {
        assertEquals(emptyList<String>(), visibleRecentIds(emptyList(), mapOf("a" to "Spor"), true))
        assertEquals(emptyList<String>(), visibleRecentIds(listOf("missing"), emptyMap(), true))
    }
}
