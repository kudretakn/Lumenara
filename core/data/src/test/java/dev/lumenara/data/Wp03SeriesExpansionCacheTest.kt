package dev.lumenara.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Wp03SeriesExpansionCacheTest {
    @Test fun missingEpisodesNeverAdvanceStampAndAValidRetryCanBeCached() = runTest {
        val now = System.currentTimeMillis()
        val expired = now - SeriesExpansionCache.SIX_HOURS_MS - 1
        var stamp = expired
        var writes = 0
        var fetches = 0
        val cache = SeriesExpansionCache({ stamp }, { _, value -> stamp = value; writes++ }, { now })
        suspend fun open(body: String) = cache.get("show", cached = { emptyList<String>() }) {
            fetches++
            wp03EpisodeSeasons(JSONObject(body))
            emptyList<String>()
        }
        repeat(2) {
            try { open("{}"); fail("Expected missing episodes to fail") }
            catch (_: SourceFailure) { }
            assertEquals(expired, stamp)
            assertEquals(0, writes)
        }
        assertEquals(emptyList<String>(), open("""{"episodes":{}}"""))
        assertEquals(now, stamp)
        assertEquals(1, writes)
        assertEquals(emptyList<String>(), open("{}")) // Warm cache: no fetch or parse.
        assertEquals(3, fetches)
        assertEquals(1, writes)
    }
}
