package dev.lumenara.data

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class UpdateState(val tag: String = "", val etag: String = "", val checkedAt: Long = 0,
    val release: String = "") {
    fun encode(): String = JSONObject().put("tag", tag).put("etag", etag)
        .put("checkedAt", checkedAt).put("release", release).toString()

    companion object {
        fun decode(value: String): UpdateState = runCatching {
            JSONObject(value).let {
                UpdateState(it.optString("tag"), it.optString("etag"), it.optLong("checkedAt"), it.optString("release"))
            }
        }.getOrDefault(UpdateState())
    }
}

// Only internal tests inject HTTP endpoints/clients. The app always uses Transport's HTTPS-only client.
internal class UpdateChecker(
    private val apiBase: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val webBase: HttpUrl = "https://github.com/".toHttpUrl(),
    private val client: OkHttpClient = Transport.client(false),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class ApiRelease(val release: JSONObject? = null, val etag: String = "",
        val unchanged: Boolean = false, val failure: SourceFailure? = null)

    suspend fun check(repository: String, current: String, preferences: Preferences,
        automatic: Boolean = false): Update? = withContext(Dispatchers.IO) {
        require(Updates.validRepo(repository))
        val claimed = preferences.beginUpdateCheck(repository, automatic, now())
        val previous = preferences.updateState(repository)
        if (!claimed) return@withContext cachedUpdate(previous, repository, current)
        val api = try {
            readApi(repository, current, previous)
        } catch (_: IOException) {
            ApiRelease(failure = SourceFailure("Sürüm denetimi başarısız. Bağlantıyı kontrol edin."))
        }
        if (api.unchanged) {
            // A 304 must preserve an available update, including after an app restart.
            val update = cachedUpdate(previous, repository, current)
            preferences.setUpdateRelease(repository, previous.tag, api.etag, previous.release)
            return@withContext update
        }
        val release = api.release ?: fallback(repository, current, checkNotNull(api.failure))
        if (api.release != null && Updates.newer(release.optString("tag_name"), current)) {
            val artifact = Updates.artifact(release, repository)
            if (Updates.digest(artifact) == null) {
                // Older assets can lack an API digest; try the same tag's web metadata.
                val web = webRelease(repository, release.optString("tag_name"))
                val assets = web.getJSONArray("assets")
                val matching = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull {
                    it.optString("browser_download_url").equals(artifact.optString("browser_download_url"), true)
                }
                artifact.put("digest", matching?.optString("digest").orEmpty())
                matching?.let { artifact.put("browser_download_url", it.getString("browser_download_url")) }
            }
        }
        val update = Updates.parseRelease(release, repository, current)
        val assets = release.optJSONArray("assets")
        val cachedAssets = JSONArray()
        if (assets != null) for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            cachedAssets.put(JSONObject().put("name", asset.optString("name"))
                .put("browser_download_url", asset.optString("browser_download_url"))
                .put("digest", asset.optString("digest")))
        }
        val cached = JSONObject().put("tag_name", release.optString("tag_name"))
            .put("body", release.optString("body").take(6000)).put("assets", cachedAssets)
        preferences.setUpdateRelease(repository, release.optString("tag_name"), api.etag, cached.toString())
        update
    }

    private fun cachedUpdate(previous: UpdateState, repository: String, current: String): Update? =
        runCatching {
            previous.release.takeIf { it.isNotEmpty() }?.let { Updates.parseRelease(JSONObject(it), repository, current) }
        }.getOrNull()

    private fun readApi(repository: String, current: String, previous: UpdateState): ApiRelease {
        val request = Request.Builder().url(apiBase.resolve("repos/$repository/releases/latest")!!)
            .header("User-Agent", "Lumenara/$current")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (previous.etag.isNotEmpty() && previous.tag.isNotEmpty()) header("If-None-Match", previous.etag) }
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 304 && previous.etag.isNotEmpty() && previous.tag.isNotEmpty() ->
                    ApiRelease(unchanged = true, etag = response.header("ETag") ?: previous.etag)
                response.code == 403 || response.code == 429 -> ApiRelease(failure = apiFailure(response))
                response.code in 500..599 -> ApiRelease(failure = httpFailure(response.code))
                !response.isSuccessful -> throw httpFailure(response.code)
                else -> try {
                    ApiRelease(JSONObject(body(response)), response.header("ETag").orEmpty())
                } catch (_: JSONException) {
                    ApiRelease(failure = SourceFailure("Sunucudan beklenmeyen bir yanıt geldi."))
                }
            }
        }
    }

    private fun apiFailure(response: Response): SourceFailure {
        val retry = response.header("Retry-After")
        val exhausted = response.header("x-ratelimit-remaining")?.trim() == "0"
        if (!exhausted && response.code != 429 && retry == null)
            return httpFailure(response.code)
        val current = response.header("Date")?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond().toDouble() }
                .getOrNull()
        } ?: (now() / 1000.0)
        val reset = response.header("x-ratelimit-reset")?.toLongOrNull()?.minus(current)
        val retrySeconds = retry?.toLongOrNull()?.toDouble() ?: retry?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - current }.getOrNull()
        }
        val seconds = if (exhausted) maxOf(reset ?: 0.0, retrySeconds ?: 0.0, 1.0)
            else maxOf(retrySeconds ?: 60.0, 1.0)
        val minutes = kotlin.math.ceil(seconds / 60).toLong()
        return SourceFailure("GitHub istek sınırına ulaşıldı, $minutes dakika sonra tekrar deneyin.")
    }

    private fun fallback(repository: String, current: String, failure: SourceFailure): JSONObject {
        val tag = try {
            latestTag(repository)
        } catch (_: IOException) {
            throw failure
        } catch (_: SourceFailure) {
            throw failure
        }
        if (!Updates.newer(tag, current)) return JSONObject().put("tag_name", tag)
        // A discovered version must never turn into an installable Update without its own digest.
        return webRelease(repository, tag)
    }

    private fun webRelease(repository: String, tag: String): JSONObject {
        val assets = try {
            expandedAssets(repository, tag)
        } catch (_: IOException) {
            throw Updates.missingDigest()
        } catch (_: SourceFailure) {
            throw Updates.missingDigest()
        }
        return JSONObject().put("tag_name", tag).put("assets", assets)
    }

    private fun latestTag(repository: String): String {
        // Inspect Location directly: downloading the release page is unnecessary.
        val latest = webBase.resolve("$repository/releases/latest")!!
        try {
            client.newBuilder().followRedirects(false).build()
                .newCall(Request.Builder().url(latest).build()).execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        response.header("Location")?.let { latest.resolve(it) }
                            ?.let { tagFromUrl(it, repository) }?.let { return it }
                    }
                }
        } catch (_: IOException) {
            // The Atom feed is independent of the latest-release redirect.
        }
        val feed = get(webBase.resolve("$repository/releases.atom")!!)
        val entry = Regex("<entry\\b[^>]*>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
            .find(feed)?.groupValues?.get(1) ?: throw SourceFailure("Sürüm denetimi başarısız.")
        return links(entry).firstNotNullOfOrNull { webBase.resolve(it)?.let { url -> tagFromUrl(url, repository) } }
            ?: throw SourceFailure("Sürüm denetimi başarısız.")
    }

    private fun tagFromUrl(url: HttpUrl, repository: String): String? {
        if (url.scheme != webBase.scheme || url.host != webBase.host || url.port != webBase.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val prefix = "/$repository/releases/tag/"
        if (!url.encodedPath.startsWith(prefix, true)) return null
        return url.encodedPath.substring(prefix.length).takeIf { Updates.version(it) != null }
    }

    private fun expandedAssets(repository: String, tag: String): JSONArray {
        val html = get(webBase.resolve("$repository/releases/expanded_assets/$tag")!!)
        val assets = JSONArray()
        val prefix = "/$repository/releases/download/$tag/"
        // Scope hashes to one asset row. A different APK's digest must never be reused.
        Regex("<li\\b[^>]*>.*?</li>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).forEach { row ->
                val digests = Regex("sha256:([0-9a-f]{64})(?![0-9a-f])", RegexOption.IGNORE_CASE)
                    .findAll(row.value).map { it.groupValues[1].lowercase() }.distinct().toList()
                links(row.value).mapNotNull { webBase.resolve(it) }.filter {
                    it.scheme == webBase.scheme && it.host == webBase.host && it.port == webBase.port &&
                        it.username.isEmpty() && it.password.isEmpty() && it.encodedPath.startsWith(prefix, true) &&
                        it.pathSegments.last().endsWith(".apk", true)
                }.distinct().singleOrNull()?.let { url ->
                    assets.put(JSONObject().put("name", url.pathSegments.last())
                        .put("browser_download_url", "https://github.com${url.encodedPath}")
                        .put("digest", digests.singleOrNull()?.let { "sha256:$it" }.orEmpty()))
                }
            }
        if (assets.length() != 1 || Updates.digest(assets.getJSONObject(0)) == null)
            throw Updates.missingDigest()
        return assets
    }

    private fun links(text: String): List<String> =
        Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1] }.toList()

    private fun get(url: HttpUrl): String = client.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw httpFailure(it.code)
        body(it)
    }

    private fun body(response: Response): String =
        bounded(checkNotNull(response.body).byteStream(), 4L * 1024 * 1024).bufferedReader().use { it.readText() }

    private fun httpFailure(code: Int) = SourceFailure("Sürüm denetimi başarısız (HTTP $code)")
}
