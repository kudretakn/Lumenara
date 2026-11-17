package dev.lumenara.tv

internal enum class LivePlaybackSignal { PROGRESS, END_REACHED, STOPPED }

/** Engine-independent policy. Callers supply elapsed (monotonic) time. */
internal fun liveShouldReconnect(
    kind: String,
    signal: LivePlaybackSignal = LivePlaybackSignal.PROGRESS,
    ownStop: Boolean = false,
    paused: Boolean = false,
    preview: Boolean = false,
    multi: Boolean = false,
    playing: Boolean = false,
    positionAdvanced: Boolean = false,
    unchangedForMs: Long = 0,
): Boolean = kind == "LIVE" && !ownStop && !paused && !preview && !multi &&
    (signal != LivePlaybackSignal.PROGRESS || playing && positionAdvanced && unchangedForMs >= 8_000)

/** Arm only after a positive position changes from a prior sample; inactive samples reset tracking. */
internal data class LiveProgress(
    val positionMs: Long? = null,
    val advancedAtMs: Long = 0,
    val positionAdvanced: Boolean = false,
) {
    fun sample(position: Long, nowMs: Long, active: Boolean): LiveProgress = when {
        !active -> LiveProgress()
        positionMs != position -> LiveProgress(position, nowMs,
            positionAdvanced || positionMs != null && position > 0)
        else -> this
    }

    fun unchangedForMs(nowMs: Long): Long = if (positionMs == null) 0 else nowMs - advancedAtMs
}
