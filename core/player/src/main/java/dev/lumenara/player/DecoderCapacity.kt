package dev.lumenara.player

/** Lazy's synchronized publication also coalesces simultaneous multiview requests. */
internal class DecoderCapacity(probe: () -> Int) {
    private val slots by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { probe().coerceIn(1, 4) }.getOrDefault(1)
    }
    fun get(): Int = slots
}
