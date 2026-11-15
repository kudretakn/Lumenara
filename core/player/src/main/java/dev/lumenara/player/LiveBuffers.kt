package dev.lumenara.player

data class LiveBuffers(val minMs: Int, val maxMs: Int, val playMs: Int, val rebufferMs: Int)

fun liveBuffers(profile: String): LiveBuffers = when (profile) {
    "Hızlı" -> LiveBuffers(8_000, 20_000, 500, 1_500)
    "Kararlı" -> LiveBuffers(30_000, 60_000, 1_500, 3_500)
    else -> LiveBuffers(15_000, 40_000, 1_000, 2_500)
}
