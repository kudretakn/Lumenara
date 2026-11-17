package dev.lumenara.player

import org.junit.Assert.*
import org.junit.Test

class SubtitleTimelineTest {
    @Test fun changingDelayCanRevisitConsumedCuesAndAdvanceWithoutASeek() {
        val cues = SubtitleTimeline<String>(false)
        cues.add(0, 2_000_000, listOf("first"))
        cues.add(3_000_000, 4_000_000, listOf("second"))
        cues.add(5_000_000, 8_000_000, listOf("third"))
        assertEquals(listOf("second"), cues.at(3_500_000))
        assertEquals(listOf("first"), cues.at(3_500_000 - 3_000_000))
        assertEquals(listOf("third"), cues.at(3_500_000 + 3_000_000))
        assertTrue(cues.at(4_500_000).isEmpty())
        assertTrue(cues.at(-100_000).isEmpty())
    }

    @Test fun mergeRetainsOverlappingCuesIncludingEqualStartsAndHonorsEnds() {
        val cues = SubtitleTimeline<String>(true)
        cues.add(0, 20, listOf("long"))
        cues.add(10, 15, listOf("short"))
        cues.add(10, 30, listOf("other"))
        cues.prune(12)
        assertEquals(listOf("long", "short", "other"), cues.at(12))
        assertEquals(listOf("long", "other"), cues.at(15))
        assertEquals(listOf("other"), cues.at(20))
    }

    @Test fun replacementNeverResurrectsAnOlderCueAndPruningKeepsIndefinitePredecessor() {
        val cues = SubtitleTimeline<String>(false)
        cues.add(0, Long.MAX_VALUE, listOf("old"))
        cues.add(10, 20, listOf("new"))
        assertTrue(cues.at(20).isEmpty())
        cues.add(30, Long.MAX_VALUE, listOf("last"))
        cues.prune(100)
        assertEquals(listOf("last"), cues.at(100))
    }
}
