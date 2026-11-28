package dev.lumenara.tv

/** Bounded 1 ms histogram; overflow stays visible in max and the capped percentile label. */
internal class Wp05FrameStats(private val budgetNs: Long) {
    private val histogram = LongArray(10_001)
    var count = 0L; private set
    var totalNs = 0L; private set
    var maxNs = 0L; private set
    var firstDrawMaxNs = 0L; private set
    var firstDrawCount = 0L; private set
    var overBudget = 0L; private set
    var over32 = 0L; private set
    var over50 = 0L; private set
    fun add(durationNs: Long, firstDraw: Boolean) {
        if (durationNs <= 0) return
        count++
        totalNs += durationNs
        maxNs = maxOf(maxNs, durationNs)
        if (firstDraw) { firstDrawCount++; firstDrawMaxNs = maxOf(firstDrawMaxNs, durationNs) }
        if (durationNs > budgetNs) overBudget++
        if (durationNs > 32_000_000) over32++
        if (durationNs > 50_000_000) over50++
        histogram[((durationNs + 999_999) / 1_000_000).coerceAtMost(10_000).toInt()]++
    }
    fun percentileUpperMs(percent: Int): Int {
        if (count == 0L) return 0
        val rank = (count * percent + 99) / 100
        var cumulative = 0L
        for (index in histogram.indices) {
            cumulative += histogram[index]
            if (cumulative >= rank) return index
        }
        return 10_000
    }
}
