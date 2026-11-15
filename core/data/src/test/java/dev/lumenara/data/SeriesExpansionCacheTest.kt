package dev.lumenara.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SeriesExpansionCacheTest {
    @Test fun reopeningUsesMemoryThenPersistentStampUntilSixHourBoundary() = runTest {
        var now = 1_000L
        var reads = 0
        var requests = 0
        val stored = mutableMapOf<String, Long>()
        fun cache() = SeriesExpansionCache(
            readStamp = { reads++; stored[it] ?: 0L },
            writeStamp = { id, stamp -> stored[id] = stamp }, clock = { now },
        )
        suspend fun open(cache: SeriesExpansionCache, id: String = "series") =
            cache.get(id, cached = { listOf("episode") }) { requests++; listOf("episode") }
        val first = cache()
        assertEquals(listOf("episode"), open(first))
        now += SeriesExpansionCache.SIX_HOURS_MS - 1
        open(first)
        assertEquals(1, reads)
        assertEquals(1, requests)
        // A new Repository/process has no memory entry, but honours DataStore's timestamp.
        val restarted = cache()
        open(restarted)
        assertEquals(2, reads)
        assertEquals(1, requests)
        now++
        open(restarted)
        assertEquals(2, requests)
        open(restarted, "another-series")
        assertEquals(3, requests)
    }

    @Test fun failedFetchIsRetriedAndEmptySuccessfulSeriesIsCached() = runTest {
        var stamp = 0L
        var requests = 0
        val cache = SeriesExpansionCache({ stamp }, { _, value -> stamp = value }, { 1_000L })
        try {
            cache.get("series", cached = { emptyList<String>() }) { requests++; error("offline") }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(0L, stamp)
        cache.get("series", cached = { emptyList<String>() }) { requests++; emptyList<String>() }
        cache.get("series", cached = { emptyList<String>() }) { requests++; emptyList<String>() }
        assertEquals(2, requests)
        assertEquals(1_000L, stamp)
    }

    @Test fun clockMovingBackwardsInvalidatesTheStamp() = runTest {
        var now = 2_000L
        val cache = SeriesExpansionCache({ 3_000L }, { _, _ -> }, { now })
        var requests = 0
        cache.get("series", cached = { "cached" }) { requests++; "fresh" }
        now--
        cache.get("series", cached = { "cached" }) { requests++; "fresh" }
        assertEquals(2, requests)
    }
}
