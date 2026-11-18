package dev.lumenara.data

import kotlinx.coroutines.flow.first

fun clampSubtitleDelay(ms: Long): Long = ms.coerceIn(-10_000L, 10_000L)

fun formatSubtitleDelay(ms: Long): String {
    val value = clampSubtitleDelay(ms)
    val magnitude = kotlin.math.abs(value)
    return "${if (value < 0) "-" else "+"}${magnitude / 1000},${magnitude % 1000 / 100} sn"
}

// Item IDs distinguish provider renditions and episodes; source stays readable for backup remapping.
fun subtitleDelayKey(item: Item): String = "subtitle_delay_${item.source}|${item.id}"

suspend fun Preferences.subtitleDelay(item: Item): Long =
    clampSubtitleDelay(text(subtitleDelayKey(item), "0").first().toLongOrNull() ?: 0L)

suspend fun Preferences.setSubtitleDelay(item: Item, ms: Long) =
    set(subtitleDelayKey(item), clampSubtitleDelay(ms).toString())
