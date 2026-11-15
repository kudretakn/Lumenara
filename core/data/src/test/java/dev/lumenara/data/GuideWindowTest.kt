package dev.lumenara.data

import dev.lumenara.catalog.Programme
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test

class GuideWindowTest {
    private val now = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    private fun programme(title: String, start: Long, end: Long) =
        """<programme channel="c" start="${stamp.format(Instant.ofEpochMilli(start))}" stop="${stamp.format(Instant.ofEpochMilli(end))}"><title>$title</title></programme>"""

    @Test fun onlyOverlappingProgrammesReachDatabaseWriter() {
        val (from, until) = guideWindow(now)
        val hour = 3_600_000L
        assertEquals(now - 12 * hour, from)
        assertEquals(now + 72 * hour, until)
        val xml = "<tv>" + listOf(
            programme("old", from - hour, from),
            programme("past overlap", from - hour, from + hour),
            programme("on air", now - hour, now + hour),
            programme("timeline +1", now + 24 * hour, now + 25 * hour),
            programme("future overlap", until - hour, until + hour),
            programme("far future", until, until + hour),
        ).joinToString("") + "</tv>"
        val written = mutableListOf<Programme>()
        readGuide(xml.byteInputStream(), now, { _, _ -> }) { written += it }
        assertEquals(listOf("past overlap", "on air", "timeline +1", "future overlap"), written.map { it.title })
    }

    @Test fun validFeedEntirelyOutsideWindowWritesNothing() {
        val (from, _) = guideWindow(now)
        var writes = 0
        readGuide(("<tv>" + programme("old", from - 7200000, from - 3600000) + "</tv>").byteInputStream(),
            now, { _, _ -> }) { writes++ }
        assertEquals(0, writes)
    }
}
