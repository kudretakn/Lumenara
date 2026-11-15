package dev.lumenara.player

data class LiveBuffers(val minMs: Int, val maxMs: Int, val playMs: Int, val rebufferMs: Int)

fun liveBuffers(profile: String): LiveBuffers = when (profile) {
    "Hızlı" -> LiveBuffers(8_000, 20_000, 500, 1_500)
    "Kararlı" -> LiveBuffers(30_000, 60_000, 1_500, 3_500)
    else -> LiveBuffers(15_000, 40_000, 1_000, 2_500)
}

data class BufferPolicy(val buffers: LiveBuffers, val targetBufferBytes: Int)

private const val MIB = 1024 * 1024

/** Use the heap class actually granted to the application, never the device's physical RAM. */
fun playerBufferBudgetBytes(memoryClassMb: Int, lowRam: Boolean): Int =
    if (lowRam) 32 * MIB
    else (memoryClassMb.toLong() * MIB * 33 / 100).coerceIn(32L * MIB, 96L * MIB).toInt()

/** Bitrate includes video + audio, in bits/second; nonpositive values mean unknown. */
fun targetBufferBytes(
    memoryClassMb: Int,
    lowRam: Boolean,
    live: Boolean,
    multi: Boolean,
    bitrate: Long = -1,
    videoHeight: Int = -1,
): Int {
    if (multi) return 8 * MIB
    val budget = playerBufferBudgetBytes(memoryClassMb, lowRam)
    if (bitrate <= 0) return if (!live || videoHeight >= 2160) budget else minOf(32 * MIB, budget)
    val seconds = if (live) 10L else 20L
    // Clamp before multiplying, including malformed/overflowing bitrate metadata.
    return (bitrate.coerceAtMost(budget.toLong() * 8) * seconds / 8)
        .coerceIn(24L * MIB, budget.toLong()).toInt()
}

fun bufferPolicy(
    profile: String,
    live: Boolean,
    multi: Boolean,
    memoryClassMb: Int = 192,
    lowRam: Boolean = false,
    bitrate: Long = -1,
    videoHeight: Int = -1,
): BufferPolicy {
    val selected = if (live) liveBuffers(profile) else when (profile) {
        "Hızlı" -> LiveBuffers(8_000, 20_000, 750, 1_500)
        "Kararlı" -> LiveBuffers(30_000, 30_000, 3_000, 5_000)
        else -> LiveBuffers(30_000, 30_000, 2_500, 5_000)
    }
    val buffers = if (multi) selected.copy(
        minMs = selected.minMs.coerceAtMost(8_000),
        maxMs = selected.maxMs.coerceAtMost(8_000),
    ) else selected
    return BufferPolicy(buffers, targetBufferBytes(memoryClassMb, lowRam, live, multi, bitrate, videoHeight))
}
