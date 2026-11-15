package dev.lumenara.data

import android.content.Context
import android.util.AtomicFile
import dev.lumenara.catalog.searchable
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
            val file = File(context.cacheDir, "tmdb/${item.id.replace(':','_')}.json")
            val previous =
                if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() else null
            val json =
                if (
                    previous != null &&
                        System.currentTimeMillis() - file.lastModified() < 7L * 86400000
                )
                    previous
                else
                    try {
                        val token = prefs.getString("access", null)?.let(vault::decrypt)
                            ?: BuildConfig.TMDB_READ_TOKEN
                        if (token.isBlank()) return@withContext local
                        val type = if (item.kind == "MOVIE") "movie" else "tv"
                        val yearSuffix =
                            Regex("""\s*[\[(]((?:19|20)\d\d)[\])]\s*$""").find(item.title)
                        val year =
                            item.year.takeIf { it.matches(Regex("(19|20)[0-9]{2}")) }
                                ?: yearSuffix?.groupValues?.get(1)
                                ?: return@withContext local
                        val title =
                            yearSuffix?.let { item.title.substring(0, it.range.first).trim() }
                                ?: item.title
                        val results =
                            fetch(token, "search/$type", mapOf("query" to title))
                                .optJSONArray("results") ?: return@withContext local
                        val match =
                            (0 until results.length())
                                .mapNotNull(results::optJSONObject)
                                .singleOrNull { row ->
                                    listOf("title", "original_title", "name", "original_name").any {
                                        searchable(row.optString(it)) == searchable(title)
                                    } &&
                                        (row.optString("release_date").ifEmpty {
                                                row.optString("first_air_date")
                                            })
                                            .take(4) == year
                                } ?: return@withContext local
                        fetch(
                                token,
                                "$type/${match.getInt("id")}",
                                mapOf("append_to_response" to "credits,videos"),
                            )
                            .also { data ->
                                file.parentFile?.mkdirs()
                                val atomic = AtomicFile(file)
                                val stream = atomic.startWrite()
                                try {
                                    stream.write(data.toString().toByteArray())
                                    atomic.finishWrite(stream)
                                } catch (e: Exception) {
                                    atomic.failWrite(stream)
                                    throw e
                                }
                            }
                    } catch (_: java.io.IOException) {
                        previous ?: return@withContext local
                    }
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

    private fun fetch(token: String, path: String, query: Map<String, String>): JSONObject {
        val builder = base.newBuilder().addPathSegments(path).addQueryParameter("language", "tr-TR")
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val request =
            Request.Builder().url(builder.build()).header("Authorization", "Bearer $token").build()
        return Transport.client(false).newCall(request).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("TMDB erişim hatası.")
            JSONObject(
                bounded(checkNotNull(it.body).byteStream(), 2 * 1024 * 1024).reader().readText()
            )
        }
    }
}
