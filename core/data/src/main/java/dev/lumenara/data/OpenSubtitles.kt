package dev.lumenara.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// Values deliberately have no generated toString: credentials must never reach diagnostics.
class SubtitleCredentials(val openKey: String = "", val username: String = "", val password: String = "", val subdlKey: String = "")
data class SubtitleQuery(val title: String, val year: String = "", val tmdb: String = "", val imdb: String = "", val season: Int? = null, val episode: Int? = null)
data class SubtitleResult(val id: String, val language: String, val release: String, val downloads: Int, val source: String, val fileName: String, val download: String = "")
data class SubtitleResults(val entries: List<SubtitleResult>, val message: String = "", val remaining: Int? = null)
class SubtitleFailure(val quota: Boolean = false, message: String = "Altyazı hizmetine ulaşılamadı.") : Exception(message)

/** HTTP can only be enabled for injected loopback endpoints used by tests. */
class SubtitleEndpoint(val api: String, val downloads: String = api, val testHttp: Boolean = false) {
    init {
        listOf(api, downloads).forEach {
            val url = it.toHttpUrl()
            require(url.isHttps || testHttp && url.host in listOf("localhost", "127.0.0.1", "::1"))
        }
    }
    internal fun permits(url: HttpUrl) = url.isHttps || testHttp && url.host in listOf("localhost", "127.0.0.1", "::1")
    internal fun execute(request: Request): ByteArray {
        require(permits(request.url))
        // Validate redirect destinations too; credential headers are never sent on a redirect.
        return Transport.client(testHttp).newBuilder().followRedirects(false).followSslRedirects(false)
            .build().newCall(request).execute().use { response ->
                if (response.code == 429 || response.code == 406) throw SubtitleFailure(true, "OpenSubtitles günlük sınırı doldu")
                if (!response.isSuccessful) throw SubtitleFailure(message = when (response.code) {
                    401, 403 -> "Altyazı hizmeti anahtarı veya giriş bilgileri kabul edilmedi."
                    else -> "Altyazı hizmeti hata verdi (HTTP ${response.code})."
                })
                bounded(checkNotNull(response.body).byteStream(), 8L * 1024 * 1024).readBytes()
            }
    }
    internal fun file(address: String): ByteArray {
        var url = address.toHttpUrl()
        repeat(6) {
            require(permits(url))
            val response = Transport.client(testHttp).newBuilder().followRedirects(false).followSslRedirects(false)
                .build().newCall(Request.Builder().url(url).build()).execute()
            response.use {
                if (it.code in listOf(301, 302, 303, 307, 308)) {
                    url = url.resolve(it.header("Location").orEmpty()) ?: throw SubtitleFailure()
                } else {
                    if (!it.isSuccessful) throw SubtitleFailure()
                    return bounded(checkNotNull(it.body).byteStream(), 8L * 1024 * 1024).readBytes()
                }
            }
        }
        throw SubtitleFailure()
    }
}

class OpenSubtitles(private val credentials: SubtitleCredentials,
                    private val endpoint: SubtitleEndpoint = SubtitleEndpoint("https://api.opensubtitles.com/api/v1/")) {
    private var token = ""
    private fun request(path: String): Request.Builder = Request.Builder()
        .url(checkNotNull(endpoint.api.toHttpUrl().resolve(path)))
        .header("Api-Key", credentials.openKey).header("User-Agent", "Lumenara v0.4")
        .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
    private fun json(request: Request) = try { JSONObject(endpoint.execute(request).toString(Charsets.UTF_8)) }
        catch (e: SubtitleFailure) { throw e } catch (_: org.json.JSONException) { throw SubtitleFailure() }
    private fun login() {
        if (token.isNotEmpty() || credentials.username.isBlank() || credentials.password.isBlank()) return
        token = json(request("login").post(JSONObject().put("username", credentials.username)
            .put("password", credentials.password).toString().toRequestBody("application/json".toMediaType())).build())
            .optString("token").takeUnless { it == "null" }.orEmpty()
        if (token.isEmpty()) throw SubtitleFailure(message = "OpenSubtitles girişi başarısız.")
    }
    fun search(query: SubtitleQuery): SubtitleResults {
        login()
        val url = checkNotNull(endpoint.api.toHttpUrl().resolve("subtitles")).newBuilder()
            .addQueryParameter("languages", "tr")
        when {
            query.tmdb.isNotBlank() -> url.addQueryParameter("tmdb_id", query.tmdb)
            query.imdb.isNotBlank() -> url.addQueryParameter("imdb_id", query.imdb.removePrefix("tt"))
            else -> { url.addQueryParameter("query", query.title); if (query.year.isNotBlank()) url.addQueryParameter("year", query.year) }
        }
        query.season?.let { url.addQueryParameter("season_number", it.toString()) }
        query.episode?.let { url.addQueryParameter("episode_number", it.toString()) }
        return parse(json(request("subtitles").url(url.build()).build()))
    }
    fun download(result: SubtitleResult): Pair<ByteArray, Int?> {
        login()
        val body = json(request("download").post(JSONObject().put("file_id", result.id.toLong())
            .toString().toRequestBody("application/json".toMediaType())).build())
        val link = body.optString("link")
        if (link.isBlank()) throw SubtitleFailure()
        return endpoint.file(link) to body.takeIf { it.has("remaining") }?.optInt("remaining")
    }
    companion object {
        fun parse(body: JSONObject): SubtitleResults {
            val data = body.optJSONArray("data")
            val rows = buildList {
                for (i in 0 until (data?.length() ?: 0)) {
                    val a = data?.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                    val files = a.optJSONArray("files") ?: continue
                    for (f in 0 until files.length()) {
                        val file = files.optJSONObject(f) ?: continue
                        val id = file.optString("file_id").toLongOrNull()?.takeIf { it > 0 } ?: continue
                        add(SubtitleResult(id.toString(), a.optString("language", "tr"), a.optString("release"),
                            a.optInt("download_count"), "OpenSubtitles", file.optString("file_name", "subtitle.srt")))
                    }
                }
            }
            return SubtitleResults(rows)
        }
    }
}
