package dev.lumenara.catalog

import org.junit.Assert.*
import org.junit.Test

class CategoryOrderTest {
    @Test fun batchOrderMatchesComparatorAndReadsEachCategoryOnce() {
        val names = listOf("TR | XXX", "Spor", "For Adults", "TR: Spor", "Haber", "Çocuk", "[TR] Belgesel")
        var reads = 0
        assertEquals(names.sortedWith(categoryOrder), names.sortedByCategory { reads++; it })
        assertEquals(names.size, reads)
    }
    @Test fun trPrefixIgnoresLeadingSymbolsButRequiresAWordBoundary() {
        val tr = listOf("TR | Ulusal", "TR: Spor", "[TR] Belgesel", "TR-Haber", "  (TR) Film")
        val other = listOf("Trabzonspor", "Travel", "Haber", "Spor", "Astral", "1 TR Haber")
        for (a in tr) for (b in other) assertTrue("$a < $b", categoryOrder.compare(a, b) < 0)
    }

    @Test fun adultCategoriesAreLastEvenWhenTheyStartWithTr() {
        val sorted = listOf("TR | XXX", "Spor", "For Adults", "TR: Spor", "Haber")
            .sortedWith(categoryOrder)
        assertEquals(listOf("TR: Spor", "Haber", "Spor"), sorted.take(3))
        assertTrue(sorted.takeLast(2).all(::isAdultCategory))
    }

    @Test fun sortsEachGroupUsingTurkishCollation() {
        assertEquals(listOf("Canlı", "Çocuk", "Işık", "İzmir", "Spor"),
            listOf("İzmir", "Spor", "Çocuk", "Işık", "Canlı").sortedWith(categoryOrder))
        assertEquals(listOf("TR | Canlı", "TR | Çocuk", "TR | Spor"),
            listOf("TR | Spor", "TR | Çocuk", "TR | Canlı").sortedWith(categoryOrder))
    }
}
