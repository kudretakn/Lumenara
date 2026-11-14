package dev.lumenara.data

import android.util.JsonReader
import dev.lumenara.catalog.Kind
import dev.lumenara.catalog.Listing
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal class Xtream(private val spec: SourceSpec) {
    private val base = spec.address.trimEnd('/').plus("/").toHttpUrl()

    private fun api(action: String = "") =
        base
            .newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", spec.user)
            .addQueryParameter("password", spec.password)
            .apply { if (action.isNotEmpty()) addQueryParameter("action", action) }

    private fun rows(action: String, consume: (Map<String, String>) -> Unit) {

        Transport.read(api(action).build().toString(), spec.allowHttp) { input, _ ->
            JsonReader(input.reader()).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
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
    }

    fun guideAddress(): String =
        base
            .newBuilder()
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", spec.user)
            .addQueryParameter("password", spec.password)
            .build()
            .toString()

    fun read(emit: (Listing) -> Unit): Int {
        val account =
            Transport.read(api().build().toString(), spec.allowHttp) { input, _ ->
                JSONObject(input.reader().readText())
            }
        if (account.optJSONObject("user_info")?.optInt("auth") != 1)
            throw SourceFailure("Xtream kullanıcı adı veya şifre kabul edilmedi.")
        val zone = account.optJSONObject("server_info")?.optString("timezone").orEmpty()
        for ((type, kind) in
            listOf("live" to Kind.LIVE, "vod" to Kind.MOVIE, "series" to Kind.SERIES)) {
            val categories = mutableMapOf<String, String>()
            rows("get_${type}_categories") { row ->
                row["category_id"]?.let { categories[it] = row["category_name"].orEmpty() }
            }
            val action = if (type == "series") "get_series" else "get_${type}_streams"
            rows(action) { row ->
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

    fun episodes(info: String, seriesTitle: String): List<Listing> =
        Transport.read(info, spec.allowHttp) { input, _ ->
            val seasons =
                JSONObject(input.reader().readText()).optJSONObject("episodes")
                    ?: return@read emptyList()
            buildList {
                for (season in seasons.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }) {
                    val episodes = seasons.optJSONArray(season) ?: continue
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.optJSONObject(i) ?: continue
                        val id = ep.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val info = ep.optJSONObject("info")
                        add(
                            Listing(
                                "$seriesTitle · S${season}E${ep.optInt("episode_num", i + 1)} · ${ep.optString("title")}",
                                stream("series", id, ep.optString("container_extension", "mp4"))
                                    .toString(),
                                "Sezon $season",
                                "",
                                Kind.EPISODE,
                                info?.optString("movie_image").orEmpty(),
                                info?.optString("plot").orEmpty(),
                                info?.optString("releasedate").orEmpty().take(4),
                                introStartMs = info?.optLong("intro_start_ms", -1) ?: -1,
                                introEndMs = info?.optLong("intro_end_ms", -1) ?: -1,
                            )
                        )
                    }
                }
            }
        }
}
