package dev.lumenara.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelCardTextTest {
    @Test fun matchingCategorySuppliesCountryContext() {
        assertEquals("7TV", channelCardName("AF: 7TV", "AF: Genel"))
        assertEquals("TRT 1", channelCardName("TR: TRT 1", "TR ➾ Ulusal"))
        assertEquals("TRT 1", channelCardName("TR: TRT 1", "TR"))
    }

    @Test fun otherCategoriesPreserveCountryPrefix() {
        listOf(null, "Tüm kanallar", "Favoriler", "Spor", "TR: Genel", "AFRICA").forEach {
            assertEquals("AF: 7TV", channelCardName("AF: 7TV", it))
        }
        assertEquals("TRT 1", channelCardName("TRT 1", "TR: Genel"))
        assertEquals("TR: ", channelCardName("TR: ", "TR: Genel"))
    }

    @Test fun fallbackUsesAtMostThreeInitialsWithoutCountryPrefix() {
        assertEquals("7TV", channelCardInitials("AF: 7TV"))
        assertEquals("BSC", channelCardInitials("TR: beIN Sports Cinema Extra"))
        assertEquals("NH", channelCardInitials("News HD"))
        assertEquals("KAN", channelCardInitials("Kanal"))
        assertEquals("TV", channelCardInitials("###"))
        assertEquals("TV", channelCardInitials(""))
    }
}
