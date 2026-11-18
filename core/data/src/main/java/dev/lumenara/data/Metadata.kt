package dev.lumenara.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

data class CastMember(val name: String, val role: String, val portrait: String)

data class MovieInfo(
    val summary: String = "",
    val year: String = "",
    val genre: String = "",
    val cast: String = "",
    val minutes: String = "",
    val rating: String = "",
    val backdrop: String = "",
    val trailer: String = "",
    val collection: String = "",
    val people: List<CastMember> = emptyList(),
) {
    companion object {
        fun from(item: Item) =
            MovieInfo(item.summary, item.year, item.genre, item.castNames, rating = item.rating)
    }
}

internal class Metadata(private val context: Context, private val vault: Vault) {
    private val prefs = context.getSharedPreferences("metadata", Context.MODE_PRIVATE)
    private val base = "https://api.themoviedb.org/3/".toHttpUrl()

    private var lastRequestAt = 0L
    private var retryAt = 0L
    private fun cacheFile(key: String) = File(context.cacheDir, "tmdb/${key.replace(':', '_')}.json")

    /** Cache-only lookup for visible/affected rows; never starts a network/catalog scan. */
    internal fun cachedMatch(item: Item): TmdbMatch? = runCatching {
        val file = cacheFile(item.id)
        if (!file.isFile) return@runCatching null
        cachedTmdbMatch(item, JSONObject(file.readText()))
    }.getOrNull()
    private val matcher = TmdbMatcher(TmdbTransport(::fetch), read = { key ->
        val file = cacheFile(key)
        if (file.isFile) runCatching { TmdbCached(JSONObject(file.readText()), file.lastModified()) }
            .getOrNull() else null
    }, write = { key, data ->
        val file = cacheFile(key)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    })
    fun configure(token: String) {
        val edit = prefs.edit()
        if (token.isBlank()) edit.remove("access")
        else edit.putString("access", vault.encrypt(token.trim()))
        check(edit.commit())
    }

    internal fun encryptedToken(): String? = prefs.getString("access", null)

    internal fun restoreEncryptedToken(token: String?) {
        check(prefs.edit().apply {
            if (token == null) remove("access") else putString("access", token)
        }.commit())
    }

    fun userConfigured() = prefs.contains("access")

    internal fun backupToken(): String = prefs.getString("access", null)?.let(vault::decrypt).orEmpty()

    fun configured() = userConfigured() || BuildConfig.TMDB_READ_TOKEN.isNotBlank()

    val hasBuiltInToken get() = BuildConfig.TMDB_READ_TOKEN.isNotBlank()

    suspend fun details(item: Item): MovieInfo =
        withContext(Dispatchers.IO) {
            val local = MovieInfo.from(item)
            if (item.kind !in listOf("MOVIE", "SERIES") || !configured()) return@withContext local
            val token = prefs.getString("access", null)?.let(vault::decrypt)
                ?: BuildConfig.TMDB_READ_TOKEN
            if (token.isBlank()) return@withContext local
            val json = StartupPerf.measure("tmdb_visible_details") {
                matcher.details(token, item.id, if (item.kind == "MOVIE") "movie" else "tv",
                    item.title, item.year, item.tmdbId())
            } ?: return@withContext local
            fun names(array: org.json.JSONArray?) =
                (0 until minOf(8, array?.length() ?: 0))
                    .mapNotNull { array?.optJSONObject(it)?.optString("name") }
                    .joinToString(", ")
            val videos = json.optJSONObject("videos")?.optJSONArray("results")
            val trailer =
                (0 until (videos?.length() ?: 0))
                    .mapNotNull { videos?.optJSONObject(it) }
                    .firstOrNull {
                        it.optString("type") == "Trailer" && it.optString("site") == "YouTube"
                    }
                    ?.optString("key")
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                    .orEmpty()
            local.copy(
                summary = local.summary.ifEmpty { json.optString("overview") },
                year =
                    local.year.ifEmpty {
                        json
                            .optString("release_date")
                            .ifEmpty { json.optString("first_air_date") }
                            .take(4)
                    },
                genre = local.genre.ifEmpty { names(json.optJSONArray("genres")) },
                cast =
                    local.cast.ifEmpty {
                        names(json.optJSONObject("credits")?.optJSONArray("cast"))
                    },
                minutes = json.optInt("runtime").takeIf { it > 0 }?.toString().orEmpty(),
                rating = local.rating.ifEmpty { json.optString("vote_average") },
                backdrop =
                    json
                        .optString("backdrop_path")
                        .takeIf { it.matches(Regex("/[A-Za-z0-9]+\\.jpg")) }
                        ?.let { "https://image.tmdb.org/t/p/w780$it" }
                        .orEmpty(),
                trailer = trailer,
                collection =
                    json.optJSONObject("belongs_to_collection")?.optString("name").orEmpty(),
                people =
                    json
                        .optJSONObject("credits")
                        ?.optJSONArray("cast")
                        ?.let { cast ->
                            (0 until minOf(12, cast.length())).mapNotNull { index ->
                                val person = cast.optJSONObject(index) ?: return@mapNotNull null
                                val name =
                                    person.optString("name").takeIf { it.isNotBlank() }
                                        ?: return@mapNotNull null
                                CastMember(
                                    name,
                                    person.optString("character"),
                                    person
                                        .optString("profile_path")
                                        .takeIf { it.matches(Regex("/[A-Za-z0-9]+\\.jpg")) }
                                        ?.let { "https://image.tmdb.org/t/p/w185$it" }
                                        .orEmpty(),
                                )
                            }
                        }
                        .orEmpty(),
            )
        }

    @Synchronized
    private fun fetch(token: String, path: String, query: Map<String, String>): JSONObject {
        val current = System.currentTimeMillis()
        if (current < retryAt) throw java.io.IOException("TMDB istek sınırı.")
        val wait = 250L - (android.os.SystemClock.elapsedRealtime() - lastRequestAt)
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = android.os.SystemClock.elapsedRealtime()
        val builder = base.newBuilder().addPathSegments(path)
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val request =
            Request.Builder().url(builder.build()).header("Authorization", "Bearer $token").build()
        return Transport.client(false).newCall(request).execute().use {
            if (it.code == 429) {
                val seconds = it.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86400) ?: 60L
                retryAt = System.currentTimeMillis() + seconds * 1000
            }
            if (!it.isSuccessful) throw java.io.IOException("TMDB erişim hatası.")
            JSONObject(
                bounded(checkNotNull(it.body).byteStream(), 2 * 1024 * 1024).reader().readText()
            )
        }
    }
}
