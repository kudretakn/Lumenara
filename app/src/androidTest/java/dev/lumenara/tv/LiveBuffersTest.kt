package dev.lumenara.tv

import dev.lumenara.player.liveBuffers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBuffersTest {
    @Test fun liveStartupThresholdsRespectTheSelectedProfile() {
        val fast = liveBuffers("Hızlı")
        val balanced = liveBuffers("Dengeli")
        val steady = liveBuffers("Kararlı")
        assertEquals(1000, balanced.playMs)
        assertEquals(2500, balanced.rebufferMs)
        assertTrue(fast.playMs < balanced.playMs && balanced.playMs < steady.playMs)
        assertTrue(fast.rebufferMs < balanced.rebufferMs && balanced.rebufferMs < steady.rebufferMs)
        for (profile in listOf(fast, balanced, steady)) {
            assertTrue(profile.playMs > 0 && profile.playMs <= profile.minMs)
            assertTrue(profile.rebufferMs <= profile.minMs && profile.minMs <= profile.maxMs)
        }
        assertEquals(balanced, liveBuffers("unknown"))
    }
}
