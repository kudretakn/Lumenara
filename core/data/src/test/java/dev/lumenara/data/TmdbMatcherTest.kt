package dev.lumenara.data

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TmdbMatcherTest {
    private val year = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneOffset.UTC).year - 3

    private class Fake : TmdbTransport {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        var answer: (String, Map<String, String>) -> JSONObject = { _, _ -> results() }
        override fun fetch(token: String, path: String, query: Map<String, String>): JSONObject {
            calls.add(path to query)
            return answer(path, query)
        }
    }

    private class Fixture {
        var now = System.currentTimeMillis()
        val fake = Fake()
        val cache = mutableMapOf<String, TmdbCached>()
        val matcher = TmdbMatcher(fake, cache::get, { key, value ->
            cache[key] = TmdbCached(value, now)
        }, { now })
        fun lookup(title: String, year: String = "", type: String = "movie", id: String = "") =
            matcher.details("fake-token", "item", type, title, year, id)
    }

    @Test fun persistingAResolvedIdAndYearReusesThePositiveCache() {
        val f = Fixture()
        f.fake.answer = { path, _ -> if (path == "movie/1") row(1, "Film") else results(row(1, "Film")) }
        assertEquals(1L, f.lookup("Film")?.getLong("id"))
        val calls = f.fake.calls.size
        assertEquals(1L, f.lookup("Film", year.toString(), id = "1")?.getLong("id"))
        assertEquals(calls, f.fake.calls.size)
        // Media kinds still have separate identities even when numeric IDs overlap.
        f.lookup("Film", year.toString(), type = "tv", id = "1")
        assertTrue(f.fake.calls.size > calls)
    }

    @Test fun turkishAlternativeTitleSurvivesProviderNoise() {
        val f = Fixture()
        f.fake.answer = { path, _ -> when (path) {
            "search/multi" -> results(row(1, "Ghost in the Shell"))
            "movie/1/alternative_titles" -> JSONObject().put("titles",
                JSONArray().put(JSONObject().put("title", "Kabuktaki Hayalet")))
            "movie/1/translations" -> JSONObject().put("translations", JSONArray())
            "movie/1" -> row(1, "Ghost in the Shell")
            else -> results()
        } }
        assertEquals(1L, f.lookup("TR: Kabuktaki Hayalet [$year] 4K [Altyazılı]")?.getLong("id"))
        assertTrue(f.fake.calls.filter { it.first.startsWith("search/") }
            .all { it.second["query"] == "Kabuktaki Hayalet" })
        assertEquals(listOf("tr-TR", "en-US", "tr-TR", "en-US", "tr-TR"),
            f.fake.calls.filter { it.first.startsWith("search/") }.map { it.second["language"] })
        assertEquals(year.toString(), f.fake.calls.first().second["year"])
    }

    @Test fun adjacentYearIsAcceptedAfterRemovingYearFilter() {
        for (offset in listOf(-1, 1)) {
            val f = Fixture()
            f.fake.answer = { path, query -> when {
                path == "search/movie" && "year" !in query -> results(row(1, "Film", year + offset))
                path == "movie/1" -> row(1, "Film", year + offset)
                else -> results()
            } }
            assertEquals(1L, f.lookup("Film", year.toString())?.getLong("id"))
            assertEquals(4, f.fake.calls.size)
        }
    }

    @Test fun wrongKindAndDistantYearAreRejectedBeforeVerification() {
        val f = Fixture()
        f.fake.answer = { path, _ -> if (path == "search/multi") results(
            row(1, "Film", type = "tv"), row(2, "Film", year + 2)) else results() }
        assertNull(f.lookup("Film", year.toString()))
        assertFalse(f.fake.calls.any { it.first.contains("alternative_titles") })
    }

    @Test fun ambiguousAliasesAreRejected() {
        val f = Fixture()
        f.fake.answer = { path, _ -> when {
            path == "search/multi" -> results(row(1, "First"), row(2, "Second"))
            path.endsWith("alternative_titles") -> JSONObject().put("titles",
                JSONArray().put(JSONObject().put("title", "Film")))
            else -> results()
        } }
        assertNull(f.lookup("Film", year.toString()))
        assertFalse(f.fake.calls.any { it.first == "movie/1" || it.first == "movie/2" })
    }

    @Test fun exactYearIsPreferredAmongVerifiedAliases() {
        val f = Fixture()
        f.fake.answer = { path, _ -> when {
            path == "search/multi" -> results(row(1, "First", year - 1), row(2, "Second"))
            path.endsWith("alternative_titles") -> JSONObject().put("titles",
                JSONArray().put(JSONObject().put("title", "Film")))
            path == "movie/2" -> row(2, "Second")
            else -> results()
        } }
        assertEquals(2L, f.lookup("Film", year.toString())?.getLong("id"))
    }

    @Test fun negativeCacheSurvivesNewMatcherAndExpires() {
        val f = Fixture()
        assertNull(f.lookup("Missing"))
        val count = f.fake.calls.size
        val reopened = TmdbMatcher(f.fake, f.cache::get, { _, _ -> }, { f.now })
        assertNull(reopened.details("fake-token", "item", "movie", "Missing", ""))
        assertEquals(count, f.fake.calls.size)
        f.now += 86400000L + 1
        assertNull(f.lookup("Missing"))
        assertTrue(f.fake.calls.size > count)
    }

    @Test fun englishStageStopsImmediatelyOnConfidentMatch() {
        val f = Fixture()
        f.fake.answer = { path, query -> when {
            path == "search/movie" && query["language"] == "en-US" -> results(row(1, "Film"))
            path == "movie/1" -> row(1, "Film")
            else -> results()
        } }
        assertNotNull(f.lookup("Film", year.toString()))
        assertEquals(listOf("search/movie", "search/movie", "movie/1"), f.fake.calls.map { it.first })
        f.lookup("Film", year.toString())
        assertEquals(3, f.fake.calls.size)
        f.now += 7L * 86400000 + 1
        f.lookup("Film", year.toString())
        assertEquals("movie/1", f.fake.calls.last().first)
        assertEquals(4, f.fake.calls.size)
    }

    @Test fun seriesSuffixAndTurkishCaseAreNormalizedAgainstTranslations() {
        val f = Fixture()
        f.fake.answer = { path, _ -> when (path) {
            "search/multi" -> results(row(1, "Other", type = "tv"))
            "tv/1/alternative_titles" -> JSONObject().put("results", JSONArray())
            "tv/1/translations" -> JSONObject().put("translations", JSONArray().put(
                JSONObject().put("data", JSONObject().put("name", "Işık İzi"))))
            "tv/1" -> row(1, "Other", type = "tv")
            else -> results()
        } }
        assertNotNull(f.lookup("TR: ISIK IZI Dublaj S01 E02", type = "tv"))
        assertTrue(f.fake.calls.filter { it.first.startsWith("search/") }
            .all { it.second["query"] == "ISIK IZI" })
    }

    @Test fun ambiguousDirectResultsDoNotFallThrough() {
        val f = Fixture()
        f.fake.answer = { _, _ -> results(row(1, "Film"), row(2, "Film")) }
        assertNull(f.lookup("Film"))
        assertEquals(1, f.fake.calls.size)
    }

    @Test fun networkFailuresAreNotNegativeCached() {
        val f = Fixture()
        f.fake.answer = { _, _ -> throw IOException("offline") }
        assertNull(f.lookup("Film"))
        assertNull(f.lookup("Film"))
        assertTrue(f.cache.isEmpty())
        assertEquals(2, f.fake.calls.size)
    }

    @Test fun providerIdSkipsSearchAndChangedIdentityInvalidatesMiss() {
        val f = Fixture()
        f.lookup("Missing")
        f.fake.calls.clear()
        f.fake.answer = { _, _ -> row(7, "Known") }
        assertEquals(7L, f.lookup("Known", id = "7")?.getLong("id"))
        assertEquals(listOf("movie/7"), f.fake.calls.map { it.first })
    }

    @Test fun unverifiedAndPaginatedCandidatesAreRejected() {
        val f = Fixture()
        f.fake.answer = { path, _ -> if (path == "search/multi")
            results(row(1, "Unrelated")) else results() }
        assertNull(f.lookup("Film"))
        val paged = Fixture()
        paged.fake.answer = { _, _ -> results(row(1, "Film")).put("total_pages", 2) }
        assertNull(paged.lookup("Film"))
    }

    private fun row(id: Int, title: String, releaseYear: Int = year, type: String = "movie") =
        JSONObject().put("id", id).put(if (type == "movie") "title" else "name", title)
            .put("media_type", type)
            .put(if (type == "movie") "release_date" else "first_air_date", "$releaseYear-01-01")

    companion object {
        private fun results(vararg rows: JSONObject) =
            JSONObject().put("results", JSONArray(rows.toList())).put("total_pages", 1)
    }
}
