package dev.lumenara.player

/** Lazy's synchronized publication also coalesces simultaneous multiview requests. */
internal class DecoderCapacity(probe: () -> Int) {
    private val slots by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { probe().coerceIn(1, 4) }.getOrDefault(1)
    }
    private val limit = java.util.concurrent.atomic.AtomicInteger(4)
    fun get(): Int = minOf(slots, limit.get())
    fun failed(activeWindows: Int): Int = limit.updateAndGet { minOf(it, (activeWindows - 1).coerceAtLeast(1)) }
}
