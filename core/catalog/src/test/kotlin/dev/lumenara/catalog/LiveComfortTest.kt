package dev.lumenara.catalog

import org.junit.Assert.*
import org.junit.Test

class LiveComfortTest {
    @Test fun retryBudgetAndBackoff() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L), (0..4).map { liveRetryDelay(it) })
        assertNull(liveRetryDelay(5))
        assertEquals(2000L, liveRetryDelay(1, true))
        assertNull(liveRetryDelay(2, true))
        assertNull(liveRetryDelay(-1))
        assertEquals(1000L, liveRetryDelay(0)) // a recovered stream starts a fresh budget
    }
    @Test fun channelNumbersUseMetadataOrAllChannelsPosition() {
        assertEquals(42, channelNumber("42", 1))
        listOf(null, "", "bad", "0", "-2").forEach { assertEquals(2, channelNumber(it, 1)) }
        assertEquals("B", resolveChannel(2, listOf("A", "B")))
        assertEquals("B", resolveChannel(42, listOf("A", "B")) { if (it == "B") "42" else null })
        assertNull(resolveChannel(3, listOf("A", "B")))
        val listings = mutableListOf<Listing>()
        Playlist.read("#EXTM3U\n#EXTINF:-1 tvg-chno=\"42\",Test\nhttps://example.com/live.ts".reader(), null, false, listings::add)
        assertEquals("42", listings.single().tvgChno)
    }
    @Test fun inputSupportsPreviousInternalZeroFourDigitsAndDeadlines() {
        assertEquals(NumberCommand.Previous, NumberInput().digit(0, 0).command)
        var state = NumberInput().digit(1, 100).state
        state = state.digit(0, 200).state
        assertEquals("10", state.digits)
        assertNull(state.tick(1699).command)
        assertEquals(NumberCommand.Select(10), state.tick(1700).command)
        assertEquals(NumberCommand.Select(10), state.confirm().command)
        state = state.digit(2, 300).state.digit(3, 400).state.digit(4, 500).state
        assertEquals("1023", state.digits)
        assertEquals(2000L, state.deadline)
        assertEquals(NumberInput(), state.confirm().state)
        assertNull(NumberInput().confirm().command)
        val missing = NumberInput().missing(1000)
        assertEquals(3000L, missing.tick(2999).state.missingUntil)
        assertEquals(NumberInput(), missing.tick(3000).state)
        assertEquals(0L, missing.digit(2, 1200).state.missingUntil)
    }
}
