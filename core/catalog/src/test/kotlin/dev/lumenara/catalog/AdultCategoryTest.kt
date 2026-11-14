package dev.lumenara.catalog

import org.junit.Assert.*
import org.junit.Test

class AdultCategoryTest {
    @Test fun matchesWordsSymbolsAndTurkishLetters() {
        listOf("Adult", "ADULTS", "For Adults", "TR | XXX", "18+", "+18", "(18)",
            "YETİŞKİN", "Yetişkin İçerik", "Erotik", "Porn", "Sex", "Film / adult / HD")
            .forEach { assertTrue(it, isAdultCategory(it)) }
    }

    @Test fun doesNotMatchSubstringsOrOrdinaryNumbers() {
        listOf("Sextant", "Sussex", "Adulthood", "Haber", "Spor", "Film 2018", "118+", "+180", "")
            .forEach { assertFalse(it, isAdultCategory(it)) }
    }
}
