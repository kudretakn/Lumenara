package dev.lumenara.tv

import org.junit.Assert.*
import org.junit.Test

class Wp05FrameStatsTest {
    @Test fun firstHeavyDrawIsIncludedInDistributionAndSlowCounts() {
        val stats = Wp05FrameStats(16_666_667)
        stats.add(2_400_000_000, true)
        repeat(4) { stats.add(16_000_000, false) }
        assertEquals(5L, stats.count)
        assertEquals(2_400_000_000L, stats.maxNs)
        assertEquals(2_400_000_000L, stats.firstDrawMaxNs)
        assertEquals(1L, stats.firstDrawCount)
        assertEquals(1L, stats.over32)
        assertEquals(1L, stats.over50)
        assertEquals(2400, stats.percentileUpperMs(95))
        assertEquals(16, stats.percentileUpperMs(50))
    }
}
