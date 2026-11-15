package dev.lumenara.catalog

import kotlin.math.abs
import kotlin.math.roundToInt

data class FrameRateMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

const val FRAME_RATE_DEFAULT = "Yalnız film ve dizi"
val frameRateOptions = listOf("Kapalı", FRAME_RATE_DEFAULT, "Her zaman")

fun shouldMatchFrameRate(setting: String, kind: String): Boolean =
    setting == "Her zaman" || setting == FRAME_RATE_DEFAULT && kind in listOf("MOVIE", "EPISODE")

/** Prefer the lowest matching multiple, keeping the current mode when equally suitable.
 * A 0.12 Hz tolerance admits fractional TV rates without treating 50 and 60 Hz as equivalent.
 */
fun matchFrameRate(frameRate: Float, current: FrameRateMode, modes: List<FrameRateMode>): FrameRateMode? {
    if (!frameRate.isFinite() || frameRate <= 0f) return null
    return modes.filter { mode ->
        if (!mode.refreshRate.isFinite() || mode.refreshRate <= 0f) return@filter false
        val multiple = (mode.refreshRate / frameRate).roundToInt()
        mode.width == current.width && mode.height == current.height &&
            mode.refreshRate.isFinite() && multiple >= 1 &&
            abs(mode.refreshRate - multiple * frameRate) <= 0.12f
    }.minWithOrNull(compareBy<FrameRateMode> { it.refreshRate }.thenBy { if (it.id == current.id) 0 else 1 })
}
