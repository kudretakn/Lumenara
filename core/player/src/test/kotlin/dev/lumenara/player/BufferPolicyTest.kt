package dev.lumenara.player

import org.junit.Test

class BufferPolicyTest {
    private val MIB = 1024 * 1024
    private val profiles = listOf("Hızlı", "Dengeli", "Kararlı", "unknown")

    @Test fun adaptiveSizesMatchBitrateAndHeap() {
        val budgets = mapOf(128 to 44_291_850, 192 to 66_437_775, 256 to 88_583_700, 512 to 96 * MIB)
        for ((memory, budget) in budgets) {
            check(playerBufferBudgetBytes(memory, false) == budget)
            for (mbps in listOf(5, 20, 60, 100)) for (live in listOf(false, true)) {
                val rate = mbps * 1_000_000L
                val expected = (rate / 8 * if (live) 10 else 20).coerceIn(24L * MIB, budget.toLong()).toInt()
                check(targetBufferBytes(memory, false, live, false, rate) == expected) {
                    "memory=$memory bitrate=$rate live=$live"
                }
                check(targetBufferBytes(memory, false, live, true, rate) == 8 * MIB)
            }
        }
        check(playerBufferBudgetBytes(16, false) == 32 * MIB)
        check(targetBufferBytes(192, false, false, false, Long.MAX_VALUE) == budgets.getValue(192))
        // Explicit examples distinguish seconds, bits and MiB.
        check(targetBufferBytes(512, false, false, false, 20_000_000) == 50_000_000)
        check(targetBufferBytes(512, false, true, false, 60_000_000) == 75_000_000)
    }

    @Test fun unknownBitratesUseSafeDefaults() {
        for (memory in listOf(128, 192, 256, 512)) for (live in listOf(false, true)) {
            val budget = playerBufferBudgetBytes(memory, false)
            for (unknown in listOf(-1L, 0L)) {
                check(targetBufferBytes(memory, false, live, false, unknown) == if (live) 32 * MIB else budget)
                check(targetBufferBytes(memory, false, live, false, unknown, 2160) == budget)
                check(targetBufferBytes(memory, false, live, false, unknown, 4320) == budget)
                check(targetBufferBytes(memory, false, live, true, unknown, 2160) == 8 * MIB)
            }
        }
    }

    @Test fun lowRamOverridesHeapClass() {
        for (memory in listOf(128, 192, 256, 512)) for (live in listOf(false, true)) {
            check(playerBufferBudgetBytes(memory, true) == 32 * MIB)
            for (rate in listOf(-1L, 0L, 5_000_000L, 20_000_000L, 60_000_000L, 100_000_000L)) {
                check(targetBufferBytes(memory, true, live, false, rate, 2160) in 24 * MIB..32 * MIB)
                check(targetBufferBytes(memory, true, live, true, rate, 2160) == 8 * MIB)
            }
        }
    }

    @Test fun multiPlaybackHasBoundedBytesAndDuration() {
        for (profile in profiles) for (live in listOf(false, true)) {
            val policy = bufferPolicy(profile, live, multi = true)
            check(policy.targetBufferBytes in 6 * MIB..8 * MIB) { "$profile live=$live: $policy" }
            check(policy.buffers.minMs <= 8_000 && policy.buffers.maxMs <= 8_000) { "$policy" }
        }
    }

    @Test fun moviesHaveAtMostThirtySeconds() {
        for (profile in profiles) for (multi in listOf(false, true)) {
            val policy = bufferPolicy(profile, live = false, multi = multi)
            check(policy.buffers.maxMs <= 30_000) { "$profile multi=$multi: $policy" }
        }
    }

    @Test fun startupThresholdsRemainValid() {
        for (profile in profiles) for (live in listOf(false, true)) for (multi in listOf(false, true)) {
            val buffers = bufferPolicy(profile, live, multi).buffers
            check(buffers.playMs in 1..buffers.minMs) { "$buffers" }
            check(buffers.rebufferMs in 1..buffers.minMs) { "$buffers" }
            check(buffers.minMs <= buffers.maxMs) { "$buffers" }
        }
    }

    @Test fun singleLivePreservesProfileDurations() {
        for (profile in profiles) {
            check(bufferPolicy(profile, live = true, multi = false).buffers == liveBuffers(profile))
        }
    }

    @Test fun unknownProfileUsesBalancedPolicy() {
        for (live in listOf(false, true)) for (multi in listOf(false, true)) {
            check(bufferPolicy("unknown", live, multi) == bufferPolicy("Dengeli", live, multi))
        }
    }
}
