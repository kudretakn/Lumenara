package dev.lumenara.data

import android.util.JsonReader
import dev.lumenara.catalog.Kind
import dev.lumenara.catalog.Listing
import dev.lumenara.catalog.episodeName
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal class Xtream(private val spec: SourceSpec, private val calls: ImportCalls? = null,
    private val spool: EncryptedSpool? = null) {
    private val base = spec.address.trimEnd('/').plus("/").toHttpUrl()

    private fun api(action: String = "") =
        base
            .newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", spec.user)
            .addQueryParameter("password", spec.password)
            .apply { if (action.isNotEmpty()) addQueryParameter("action", action) }

    internal class Snapshot(val account: JSONObject, val bodies: Map<String, Download>, val state: FetchState,
        val unchanged: Boolean, val checkActive: () -> Unit = {}) : java.io.Closeable {
        override fun close() { bodies.values.forEach { it.close() } }
    }

    // Xtream validators are unreliable. Download each endpoint once, then compare the whole catalog.
    // Account timestamps/online status are deliberately excluded from the catalog digest.
    fun prepare(cache: java.io.File, previous: FetchState, progress: (String) -> Unit = {},
        checkActive: () -> Unit = {}): Snapshot {
        checkActive()
        progress("Hesap doğrulanıyor…")
        val account = account()
        val bodies = linkedMapOf<String, Download>()
        val bodySpool = spool ?: EncryptedSpool.ephemeral()
        try {
            for (type in listOf("live", "vod", "series")) {
                val stage = when (type) {
                    "live" -> "Canlı kanallar indiriliyor"
                    "vod" -> "Filmler indiriliyor"
                    else -> "Diziler indiriliyor"
                }
                for (action in listOf("get_${type}_categories", if (type == "series") "get_series" else "get_${type}_streams"))
                    bodies[action] = Transport.download(api(action).build().toString(), spec.allowHttp, cache,
                        checkActive = checkActive, calls = calls, spool = bodySpool,
                        progress = { bytes -> progress("$stage · ${bytes / (1024 * 1024)} MB") })
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            bodies.forEach { (action, body) -> digest.update("$action:${body.state.digest}\n".toByteArray(Charsets.UTF_8)) }
            digest.update(account.optJSONObject("server_info")?.optString("timezone").orEmpty().toByteArray(Charsets.UTF_8))
            val state = FetchState(digest = digest.digest().joinToString("") { "%02x".format(it) })
            checkActive()
            return Snapshot(account, bodies, state, state.digest == previous.digest, checkActive)
        } catch (error: Throwable) {
            bodies.values.forEach { it.close() }
            throw error
        }
    }

    private fun rows(action: String, snapshot: Snapshot?, consume: (Map<String, String>) -> Unit) {
        val parse: (java.io.InputStream, String) -> Unit = { input, _ ->
            JsonReader(input.reader()).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    snapshot?.checkActive?.invoke()
                    val row = mutableMapOf<String, String>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        when (reader.peek()) {
                            android.util.JsonToken.STRING,
                            android.util.JsonToken.NUMBER -> row[name] = reader.nextString()
                            android.util.JsonToken.BEGIN_ARRAY -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    if (
                                        name == "backdrop_path" &&
                                            row[name] == null &&
                                            reader.peek() == android.util.JsonToken.STRING
                                    ) {
                                        row[name] = reader.nextString()
                                    } else reader.skipValue()
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    consume(row)
                }
                reader.endArray()
            }
        }
        if (snapshot != null) snapshot.bodies.getValue(action).read(parse)
        else Transport.read(api(action).build().toString(), spec.allowHttp, calls, parse)
    }

    fun guideAddress(): String =
        base
            .newBuilder()
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", spec.user)
            .addQueryParameter("password", spec.password)
            .build()
            .toString()

    private fun account(): JSONObject {
        val account =
            Transport.read(api().build().toString(), spec.allowHttp, calls) { input, _ ->
                JSONObject(input.reader().readText())
            }
        if (account.optJSONObject("user_info")?.optInt("auth") != 1)
            throw SourceFailure("Xtream kullanıcı adı veya şifre kabul edilmedi.")
        return account
    }

    fun read(emit: (Listing) -> Unit): Int = read(null, emit)

    fun read(snapshot: Snapshot?, emit: (Listing) -> Unit): Int {
        val account = snapshot?.account ?: account()
        val zone = account.optJSONObject("server_info")?.optString("timezone").orEmpty()
        for ((type, kind) in
            listOf("live" to Kind.LIVE, "vod" to Kind.MOVIE, "series" to Kind.SERIES)) {
            val categories = mutableMapOf<String, String>()
            rows("get_${type}_categories", snapshot) { row ->
                row["category_id"]?.let { categories[it] = row["category_name"].orEmpty() }
            }
            val action = if (type == "series") "get_series" else "get_${type}_streams"
            rows(action, snapshot) { row ->
                val id = row[if (kind == Kind.SERIES) "series_id" else "stream_id"] ?: return@rows
                val url =
                    if (kind == Kind.SERIES)
                        api("get_series_info").addQueryParameter("series_id", id).build()
                    else
                        stream(
                            if (kind == Kind.LIVE) "live" else "movie",
                            id,
                            row["container_extension"] ?: if (kind == Kind.LIVE) "ts" else "mp4",
                        )
                emit(
                    Listing(
                        row["name"] ?: return@rows,
                        url.toString(),
                        categories[row["category_id"]] ?: "Diğer",
                        row["epg_channel_id"].orEmpty().ifBlank { id },
                        kind,
                        (row["stream_icon"] ?: row["cover"]).orEmpty(),
                        row["plot"].orEmpty(),
                        (row["year"] ?: row["releaseDate"] ?: row["release_date"])
                            .orEmpty()
                            .take(4),
                        row["genre"].orEmpty(),
                        row["rating"].orEmpty(),
                        row["language"].orEmpty(),
                        row["cast"].orEmpty(),
                        tmdbId = row["tmdb_id"] ?: row["tmdb"].orEmpty(),
                        providerId = id,
                        archive =
                            if (kind == Kind.LIVE && row["tv_archive"] == "1" && zone.isNotBlank())
                                base
                                    .newBuilder()
                                    .addPathSegment("timeshift")
                                    .addPathSegment(spec.user)
                                    .addPathSegment(spec.password)
                                    .build()
                                    .toString() + "/{minutes}/{providerTime}/$id.ts"
                            else "",
                        days = row["tv_archive_duration"]?.toIntOrNull() ?: 0,
                        zone = zone,
                        backdrop = row["backdrop_path"].orEmpty().takeIf { it.startsWith("https://", true) }.orEmpty(),
                    )
                )
            }
        }
        return account.optJSONObject("user_info")?.optInt("max_connections", 0) ?: 0
    }

    private fun stream(type: String, id: String, ext: String): HttpUrl =
        base
            .newBuilder()
            .addPathSegment(type)
            .addPathSegment(spec.user)
            .addPathSegment(spec.password)
            .addPathSegment("$id.$ext")
            .build()

    fun subtitleInfo(address: String, kind: String, title: String, year: String): SubtitleQuery {
        val url = if (kind == "SERIES") address.toHttpUrl() else {
            val id = address.toHttpUrl().pathSegments.last().substringBeforeLast('.')
            api("get_vod_info").addQueryParameter("vod_id", id).build()
        }
        return Transport.read(url.toString(), spec.allowHttp, calls) { input, _ ->
            val root = JSONObject(input.reader().readText())
            val info = root.optJSONObject("info") ?: root.optJSONObject("movie_data") ?: root
            fun number(vararg names: String) = names.asSequence().map { info.optString(it) }
                .firstOrNull { it.toLongOrNull()?.let { n -> n > 0 } == true }.orEmpty()
            val tmdb = number("tmdb_id", "tmdb", "tmdb_number")
            val imdb = info.optString("imdb_id").ifBlank { info.optString("imdb") }
                .let { if (it.matches(Regex("[0-9]+"))) "tt$it" else it }
                .takeIf { it.matches(Regex("tt[0-9]+")) }.orEmpty()
            val releaseYear = year.ifBlank {
                listOf("releasedate", "release_date", "releaseDate", "year").asSequence()
                    .map { info.optString(it).take(4) }.firstOrNull { it.matches(Regex("(?:19|20)[0-9]{2}")) }.orEmpty()
            }
            SubtitleQuery(title, releaseYear, tmdb, imdb)
        }
    }

    fun episodes(info: String, seriesTitle: String): List<Listing> =
        Transport.read(info, spec.allowHttp, calls) { input, _ ->
            val seriesId = info.toHttpUrl().queryParameter("series_id").orEmpty()
            val seasons = wp03EpisodeSeasons(JSONObject(input.reader().readText()))
            buildList {
                for (season in seasons.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }) {
                    val episodes = seasons.optJSONArray(season) ?: continue
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.optJSONObject(i) ?: continue
                        val id = ep.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val info = ep.optJSONObject("info")
                        add(
                            Listing(
                                "$seriesTitle · S${ep.optInt("season", season.toIntOrNull() ?: 1)}E${ep.optInt("episode_num", i + 1)} · ${ep.optString("title")}",
                                stream("series", id, ep.optString("container_extension", "mp4"))
                                    .toString(),
                                "Sezon ${ep.optInt("season", season.toIntOrNull() ?: 1)}",
                                "",
                                Kind.EPISODE,
                                info?.optString("movie_image").orEmpty(),
                                info?.optString("plot").orEmpty(),
                                info?.optString("releasedate").orEmpty().take(4),
                                durationMs = (info?.optLong("duration_secs", 0)?.takeIf { it > 0 }
                                    ?: info?.optString("duration")?.split(':')?.mapNotNull { it.toLongOrNull() }
                                        ?.takeIf { it.size == 3 }?.let { it[0] * 3600 + it[1] * 60 + it[2] }
                                    ?: 0) * 1000,
                                introStartMs = info?.optLong("intro_start_ms", -1) ?: -1,
                                introEndMs = info?.optLong("intro_end_ms", -1) ?: -1,
                                providerId = "$seriesId:$id",
                            )
                        )
                    }
                }
            }.sortedWith(compareBy({ episodeName(it.title).season }, { episodeName(it.title).episode }, { it.url }))
        }
}
