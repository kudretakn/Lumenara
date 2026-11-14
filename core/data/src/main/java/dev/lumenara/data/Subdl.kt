package dev.lumenara.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class Subdl(private val key: String,
            private val endpoint: SubtitleEndpoint = SubtitleEndpoint("https://api.subdl.com/api/v1/", "https://dl.subdl.com/")) {
    fun search(query: SubtitleQuery): SubtitleResults {
        val url = checkNotNull(endpoint.api.toHttpUrl().resolve("subtitles")).newBuilder()
            .addQueryParameter("api_key", key).addQueryParameter("languages", "TR")
            .addQueryParameter("type", if (query.season == null) "movie" else "tv")
        when {
            query.tmdb.isNotBlank() -> url.addQueryParameter("tmdb_id", query.tmdb)
            query.imdb.isNotBlank() -> url.addQueryParameter("imdb_id", query.imdb)
            else -> { url.addQueryParameter("film_name", query.title); if (query.year.isNotBlank()) url.addQueryParameter("year", query.year) }
        }
        query.season?.let { url.addQueryParameter("season_number", it.toString()) }
        query.episode?.let { url.addQueryParameter("episode_number", it.toString()) }
        return try { parse(JSONObject(endpoint.execute(Request.Builder().url(url.build()).build()).toString(Charsets.UTF_8))) }
            catch (e: SubtitleFailure) {
                if (e.quota) throw SubtitleFailure(true, "Subdl günlük sınırı doldu")
                throw e
            }
            catch (_: org.json.JSONException) { throw SubtitleFailure() }
    }
    fun download(result: SubtitleResult): Pair<ByteArray, String> {
        val base = endpoint.downloads.toHttpUrl()
        val path = result.download
        // API results may contain /subtitle/foo.zip or a bare archive name.
        val link = if (path.startsWith("https://") || path.startsWith("http://")) path.toHttpUrl()
            else checkNotNull(base.resolve(if (path.startsWith("/") || path.startsWith("subtitle/")) path else "subtitle/${path.trimStart('/')}"))
        require(link.host == base.host && link.port == base.port)
        return unzip(endpoint.file(link.toString()))
    }
    companion object {
        fun parse(body: JSONObject): SubtitleResults {
            if (body.has("status") && !body.optBoolean("status", true)) {
                val message = body.optString("message").lowercase()
                if (listOf("quota", "limit", "rate").any { it in message }) throw SubtitleFailure(true, "Subdl günlük sınırı doldu")
                throw SubtitleFailure(message = "Subdl araması tamamlanamadı.")
            }
            val array = body.optJSONArray("subtitles") ?: body.optJSONObject("data")?.optJSONArray("subtitles")
                ?: body.optJSONArray("data")
            return SubtitleResults(buildList {
                for (i in 0 until (array?.length() ?: 0)) {
                    val row = array?.optJSONObject(i) ?: continue
                    val url = row.optString("url").ifBlank { row.optString("download_url") }
                    if (url.isBlank() || url == "null") continue
                    val release = row.optString("release_name").ifBlank { row.optString("name", "Altyazı") }
                    add(SubtitleResult(url, row.optString("language", "TR"), release,
                        row.optInt("download_count", row.optInt("downloads")), "Subdl", "$release.srt", url))
                }
            })
        }
        internal fun unzip(bytes: ByteArray): Pair<ByteArray, String> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            var expanded = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > 100) throw SubtitleFailure()
                if (entry.isDirectory) continue
                val remaining = 16L * 1024 * 1024 - expanded
                val data = bounded(zip, remaining).readBytes()
                expanded += data.size
                if (entry.name.substringAfterLast('.').lowercase() in listOf("srt", "vtt", "ass", "ssa"))
                    return@use data to entry.name.substringAfterLast('/')
            }
            throw SubtitleFailure(message = "Arşivde desteklenen altyazı bulunamadı.")
        }
    }
}
