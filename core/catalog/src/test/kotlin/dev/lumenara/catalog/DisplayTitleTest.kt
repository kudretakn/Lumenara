package dev.lumenara.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTitleTest {
    @Test fun stripsRepeatedProviderPrefixesAndLongIds() {
        assertEquals("Kıyı (Dublaj)", "[TR] VOD | Kıyı 104439686826 (Dublaj)".displayTitle())
        assertEquals("Kıyı", "Sağlayıcı: Kıyı [123456]".displayTitle())
        assertEquals("Kıyı", "IPTV - 123456 Kıyı".displayTitle())
    }

    @Test fun preservesShortNumbersAndEpisodeNames() {
        assertEquals("2001: Bir Uzay Destanı", "2001: Bir Uzay Destanı".displayTitle())
        assertEquals("1917", "1917".displayTitle())
        assertEquals("Kıyı S1E12", "Kıyı S1E12".displayTitle())
        assertEquals("12345 Gece", "12345 Gece".displayTitle())
    }

    @Test fun normalizesWhitespaceAndHandlesEmptyTitles() {
        assertEquals("Kıyı Günlüğü", "  Kıyı   Günlüğü  ".displayTitle())
        assertEquals("", "123456789".displayTitle())
        assertEquals("", "".displayTitle())
    }
}
