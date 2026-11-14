package dev.lumenara.catalog

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Catchup {
    fun address(
        template: String,
        days: Int,
        start: Long,
        end: Long,
        now: Long,
        zone: String,
        allowHttp: Boolean,
    ): String? {
        if (
            template.isBlank() ||
                days <= 0 ||
                start >= end ||
                end > now ||
                start < now - days.coerceAtMost(365) * 86400000L
        )
            return null
        var url = template
        if ("{providerTime}" in url) {
            val providerZone = runCatching { ZoneId.of(zone) }.getOrNull() ?: return null
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").withZone(providerZone)
            url = url.replace("{providerTime}", formatter.format(Instant.ofEpochMilli(start)))
        }
        val substitutions =
            mapOf(
                "utc" to start / 1000,
                "start" to start / 1000,
                "utcend" to end / 1000,
                "end" to end / 1000,
                "duration" to (end - start) / 1000,
                "minutes" to (end - start + 59999) / 60000,
            )
        if (url == template && substitutions.keys.none { "{$it}" in url }) return null
        substitutions.forEach { (key, value) -> url = url.replace("{$key}", value.toString()) }
        if ('{' in url || '}' in url) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf {
            uri.host != null && (uri.scheme == "https" || allowHttp && uri.scheme == "http")
        }
    }
}
