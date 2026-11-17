package dev.lumenara.catalog

import java.io.Reader
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class Kind {
    LIVE,
    MOVIE,
    SERIES,
    EPISODE,
}

data class Listing(
    val title: String,
    val url: String,
    val group: String,
    val tvgId: String,
    val kind: Kind,
    val logo: String = "",
    val summary: String = "",
    val year: String = "",
    val genre: String = "",
    val rating: String = "",
    val language: String = "",
    val cast: String = "",
    val archive: String = "",
    val days: Int = 0,
    val zone: String = "",
    val introStartMs: Long = -1,
    val introEndMs: Long = -1,
    val backdrop: String = "",
    val tvgChno: String = "",
    val parentUrl: String = "",
    val durationMs: Long = 0,
    val tmdbId: String = "",
)

fun canSkipIntro(start: Long, end: Long, position: Long, duration: Long) =
    start >= 0 && end > start && end < duration && position in start until end

private val searchLocale = Locale.forLanguageTag("tr")
private val searchMarks = Regex("\\p{M}+")

fun searchable(value: String): String =
    Normalizer.normalize(value.lowercase(searchLocale), Normalizer.Form.NFD)
        .replace(searchMarks, "")
        .replace('ı', 'i')
        .trim()

fun contentKey(source: String, address: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8))
    return source + ":" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

object Playlist {
    private val attribute = Regex("""([\w-]+)\s*=\s*"([^"]*)"""".trimIndent())
    private val videoFile = Regex("""(?i)\.(mp4|mkv|avi|mov|webm)$""")
    private val seriesGroup = Regex("""\b(dizi\w*|series|sezon\w*)\b""")
    private val movieGroup = Regex("""\b(vod|film\w*|movie\w*|sinema\w*)\b""")

    fun read(reader: Reader, base: URI?, allowHttp: Boolean, emit: (Listing) -> Unit) {
        var info = ""
        reader.buffered().forEachLine { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTINF:", true) -> info = line
                line.startsWith("#EXT-X-") ->
                    throw IllegalArgumentException(
                        "Bu adres kanal listesi yerine tek bir HLS yayını içeriyor."
                    )
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val metadata = info
                    info = ""
                    val uri = runCatching { (base?.resolve(line) ?: URI(line)) }.getOrNull()
                    if (
                        uri?.host == null ||
                            !(uri.scheme.equals("https", true) ||
                                allowHttp && uri.scheme.equals("http", true))
                    )
                        return@forEachLine
                    val fields =
                        attribute.findAll(metadata).associate {
                            it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2]
                        }
                    var quoted = false
                    val comma = metadata.indexOfFirst {
                        if (it == '"') quoted = !quoted
                        it == ',' && !quoted
                    }
                    val title =
                        (if (comma >= 0) metadata.substring(comma + 1).trim() else "")
                            .ifEmpty { fields["tvg-name"].orEmpty() }
                            .ifEmpty { "Adsız yayın" }
                    val group = fields["group-title"].orEmpty().ifBlank { "Diğer" }
                    val kind =
                        when {
                            uri.path.contains("/series/", true) || hasEpisodeNumber(title) ||
                                seriesGroup.containsMatchIn(searchable(group)) ->
                                Kind.EPISODE
                            uri.path.contains("/movie/", true) || movieGroup.containsMatchIn(searchable(group)) ||
                                videoFile.containsMatchIn(uri.path) ->
                                Kind.MOVIE
                            else -> Kind.LIVE
                        }
                    emit(
                        Listing(
                            title,
                            uri.toASCIIString(),
                            group,
                            fields["tvg-id"].orEmpty(),
                            kind,
                            fields["tvg-logo"].orEmpty(),
                            tvgChno = fields["tvg-chno"].orEmpty(),
                            language = fields["tvg-language"].orEmpty(),
                            archive = fields["catchup-source"].orEmpty(),
                            days = fields["catchup-days"]?.toIntOrNull()?.coerceIn(0, 365) ?: 0,
                        )
                    )
                }
            }
        }
    }
}
