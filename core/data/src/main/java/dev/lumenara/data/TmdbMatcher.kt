package dev.lumenara.data

import dev.lumenara.catalog.productionName
import dev.lumenara.catalog.searchable
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal fun interface TmdbTransport {
    fun fetch(token: String, path: String, query: Map<String, String>): JSONObject
}

internal data class TmdbCached(val data: JSONObject, val savedAt: Long)

/** Uses the existing metadata files; misses are deliberately shorter lived than details. */
internal class TmdbMatcher(
    private val transport: TmdbTransport,
    private val read: (String) -> TmdbCached?,
    private val write: (String, JSONObject) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Serialize cache misses as well as searches, including simultaneous opens of one item.
    @Synchronized
    fun details(token: String, key: String, type: String, title: String, year: String,
        knownId: String = ""): JSONObject? {
        require(type == "movie" || type == "tv")
        val name = productionName(episodeSuffix.replace(title, ""), year)
        val identity = "$type|${normalized(name.title)}|${name.year}|$knownId"
        val previous = read(key)?.takeIf {
            !it.data.has("_identity") || it.data.optString("_identity") == identity
        }
        val missed = previous?.data?.optBoolean("_miss") == true
        val ttl = if (missed) 86400000L else 7L * 86400000
        if (previous != null && now() - previous.savedAt in 0 until ttl)
            return previous.data.takeUnless { missed }
        try {
            // A stale positive is refreshed by ID, without repeating any title searches.
            val id = knownId.toLongOrNull()?.takeIf { it > 0 }
                ?: previous?.data?.optLong("id")?.takeIf { it > 0 }
                ?: find(token, type, name.title, name.year.toIntOrNull())
            val data = if (id == null) JSONObject().put("_miss", true) else
                transport.fetch(token, "$type/$id", mapOf("language" to "tr-TR",
                    "append_to_response" to "credits,videos"))
            data.put("_identity", identity)
            write(key, data)
            return data.takeUnless { it.optBoolean("_miss") }
        } catch (_: IOException) {
            // Network errors and rate limits are not evidence of a missing title.
            return previous?.data?.takeUnless { missed }
        }
    }

    private fun find(token: String, type: String, title: String, year: Int?): Long? {
        val target = normalized(title)
        if (target.isBlank()) return null
        val yearKey = if (type == "movie") "year" else "first_air_date_year"
        val stages = buildList {
            if (year != null) {
                add(mapOf("language" to "tr-TR", yearKey to year.toString()))
                add(mapOf("language" to "en-US", yearKey to year.toString()))
            }
            add(mapOf("language" to "tr-TR"))
            add(mapOf("language" to "en-US"))
        }
        for (stage in stages) {
            val result = transport.fetch(token, "search/$type", stage + ("query" to title))
            val matches = rows(result.optJSONArray("results")).filter {
                eligible(it, type, year) && titles(it).any { text -> normalized(text) == target }
            }.distinctBy { it.optLong("id") }
            // Never resolve an already ambiguous exact title through a later, narrower result.
            if (matches.size > 1) return null
            if (matches.size == 1 && result.optInt("total_pages", 1) <= 1)
                return matches.single().optLong("id")
        }
        val multi = transport.fetch(token, "search/multi",
            mapOf("query" to title, "language" to "tr-TR"))
        // Bound fan-out on TV hardware; unseen candidates cannot safely be ruled out.
        if (multi.optInt("total_pages", 1) > 1) return null
        val candidates = rows(multi.optJSONArray("results")).filter {
            it.optString("media_type") == type && eligible(it, type, year)
        }.distinctBy { it.optLong("id") }
        if (candidates.size > 8) return null
        val verified = candidates.filter { row ->
            val id = row.optLong("id")
            val alternative = transport.fetch(token, "$type/$id/alternative_titles", emptyMap())
            val translations = transport.fetch(token, "$type/$id/translations", emptyMap())
            val aliases = rows(alternative.optJSONArray(if (type == "movie") "titles" else "results"))
                .map { it.optString("title") } +
                rows(translations.optJSONArray("translations")).flatMap {
                    it.optJSONObject("data")?.let(::titles).orEmpty()
                }
            // Confidence threshold: exact folded alias (1.0); fuzzy similarity is never enough.
            aliases.any { normalized(it) == target }
        }
        if (verified.size == 1) return verified.single().optLong("id")
        // Exact year outranks adjacent-year aliases, but ties remain unresolved.
        return verified.filter { dateYear(it, type) == year && year != null }
            .singleOrNull()?.optLong("id")
    }

    private fun eligible(row: JSONObject, type: String, year: Int?): Boolean =
        row.optLong("id") > 0 && row.optString("media_type", type) == type &&
            (year == null || dateYear(row, type)?.let { kotlin.math.abs(it - year) <= 1 } == true)

    private fun dateYear(row: JSONObject, type: String): Int? =
        row.optString(if (type == "movie") "release_date" else "first_air_date").take(4).toIntOrNull()

    private fun titles(row: JSONObject): List<String> =
        listOf("title", "original_title", "name", "original_name").map { row.optString(it) }

    private fun rows(array: JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

    private fun normalized(value: String): String =
        searchable(value).replace(nonLetters, " ").trim().replace(spaces, " ")

    companion object {
        private val episodeSuffix = Regex("""(?i)\s*[-|:]?\s*S\d{1,3}\s*E\d{1,3}\b.*$""")
        private val nonLetters = Regex("[^\\p{L}\\p{N}]+")
        private val spaces = Regex("\\s+")
    }
}
