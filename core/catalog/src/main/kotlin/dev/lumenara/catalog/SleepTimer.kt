package dev.lumenara.catalog

/** Playback-scoped timer. The caller owns its lifetime, so leaving playback resets it. */
class SleepTimer(private val now: () -> Long = System::currentTimeMillis) {
    enum class Choice(val label: String, val minutes: Long?) {
        OFF("Kapalı", null), MINUTES_15("15 dk", 15), MINUTES_30("30 dk", 30),
        MINUTES_60("60 dk", 60), MINUTES_90("90 dk", 90), EPISODE("Bölüm sonunda", null)
    }

    private var deadline: Long? = null
    var choice: Choice = Choice.OFF
        private set

    fun select(value: Choice) {
        choice = value
        deadline = value.minutes?.let { now() + it * 60_000L }
    }

    fun remainingMs(): Long? = deadline?.let { (it - now()).coerceAtLeast(0) }
    fun showCountdown(): Boolean = remainingMs()?.let { it in 1..60_000 } == true

    fun addFifteenMinutes() {
        val base = deadline ?: return
        deadline = maxOf(base, now()) + 15 * 60_000L
        choice = Choice.MINUTES_15
    }

    fun cancel() { select(Choice.OFF) }

    /** Returns true once a timed or episode-end timer has expired. */
    fun poll(): Boolean {
        if (deadline?.let { now() >= it } == true) {
            cancel()
            return true
        }
        return false
    }

    fun episodeEnded(): Boolean {
        if (choice != Choice.EPISODE) return false
        cancel()
        return true
    }
}
