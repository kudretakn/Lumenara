package dev.lumenara.catalog

/** Season/episode numbers live in provider titles; no database columns are required. */
data class EpisodeName(val series: String, val season: Int, val episode: Int, val title: String)

private val numberedEpisode = Regex("""(?iu)\bS(\d{1,3})\s*E(\d{1,4})\b""")
private val turkishEpisode = Regex("""(?iu)(\d{1,3})\s*\.?\s*Sezon\s*(\d{1,4})\s*\.?\s*Bölüm""")

fun episodeName(title: String, category: String = ""): EpisodeName {
    val match = numberedEpisode.find(title) ?: turkishEpisode.find(title)
    val series = match?.let { title.substring(0, it.range.first).trim(' ', '-', '·', ':') }
        .orEmpty().ifBlank { title }
    return EpisodeName(series,
        match?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\d+").find(category)?.value?.toIntOrNull() ?: 1,
        match?.groupValues?.get(2)?.toIntOrNull() ?: 0,
        match?.let { title.substring(it.range.last + 1).trim(' ', '-', '·', ':') }
            .orEmpty().ifBlank { title })
}

fun hasEpisodeNumber(title: String) = numberedEpisode.containsMatchIn(title) || turkishEpisode.containsMatchIn(title)

fun watched(position: Long, duration: Long): Boolean =
    duration > 0 && position >= duration * .90

fun resumePosition(position: Long, duration: Long): Long =
    if (watched(position, duration)) 0 else position.coerceAtLeast(0)

data class SeriesEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val position: Long = 0,
    val duration: Long = 0,
    val updated: Long = 0,
)

fun orderedEpisodes(episodes: List<SeriesEpisode>): List<SeriesEpisode> =
    episodes.sortedWith(compareBy({ it.season }, { it.episode }, { it.id }))

/** Prefer the most recently active unfinished episode, then advance across season boundaries. */
fun continueEpisode(episodes: List<SeriesEpisode>): SeriesEpisode? {
    val ordered = orderedEpisodes(episodes)
    ordered.filter { it.position > 0 && !watched(it.position, it.duration) }
        .maxWithOrNull(compareBy<SeriesEpisode> { it.updated }.thenBy { it.id })?.let { return it }
    val latest = ordered.filter { it.position > 0 }
        .maxWithOrNull(compareBy<SeriesEpisode> { it.updated }.thenBy { it.season }.thenBy { it.episode })
    if (latest != null) {
        ordered.drop(ordered.indexOf(latest) + 1)
            .firstOrNull { !watched(it.position, it.duration) }?.let { return it }
    }
    return ordered.firstOrNull { !watched(it.position, it.duration) } ?: ordered.firstOrNull()
}

fun nextEpisode(episodes: List<SeriesEpisode>, currentId: String): SeriesEpisode? {
    val ordered = orderedEpisodes(episodes)
    val index = ordered.indexOfFirst { it.id == currentId }
    return if (index < 0) null else ordered.getOrNull(index + 1)
}

/** Streaming M3U import adapter: one virtual series per normalized title, within a source. */
class SeriesGrouper {
    private val series = mutableSetOf<String>()
    private val counts = mutableMapOf<String, Int>()

    fun accept(row: Listing, emit: (Listing) -> Unit) {
        if (row.kind != Kind.EPISODE) { emit(row); return }
        val name = episodeName(row.title, row.group)
        val key = searchable(name.series).replace(Regex("\\s+"), " ")
        val parentUrl = "https://series.invalid/" + contentKey("m3u", key).substringAfter(':')
        if (series.add(parentUrl)) emit(row.copy(title = name.series, url = parentUrl,
            kind = Kind.SERIES, tvgId = "", parentUrl = ""))
        val countKey = "$parentUrl:${name.season}"
        val number = name.episode.takeIf { it > 0 } ?: ((counts[countKey] ?: 0) + 1)
        counts[countKey] = maxOf(counts[countKey] ?: 0, number)
        emit(row.copy(title = "${name.series} · S${name.season}E$number · ${name.title}",
            group = "Sezon ${name.season}", parentUrl = parentUrl))
    }
}
