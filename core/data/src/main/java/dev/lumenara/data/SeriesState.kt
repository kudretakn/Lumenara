package dev.lumenara.data

import dev.lumenara.catalog.*

fun Item.seriesEpisode(mark: Mark? = null): SeriesEpisode {
    val name = wp22EpisodeName()
    return SeriesEpisode(id, name.season, name.episode,
        mark?.position ?: 0, mark?.duration ?: 0, mark?.updated ?: 0)
}

private val wp22EpisodeOrder = compareBy<Item>({ it.episodeSeason }, { it.episodeNumber }, { it.id })

fun List<Item>.seriesOrder(): List<Item> {
    if (all { it.episodeVersion == 1 }) {
        // DAO results already have this order. Preserve the list without sorting or copying.
        if ((1 until size).all { wp22EpisodeOrder.compare(this[it - 1], this[it]) <= 0 }) return this
        return sortedWith(wp22EpisodeOrder)
    }
    return map { it to it.seriesEpisode() }
        .sortedWith(compareBy({ it.second.season }, { it.second.episode }, { it.second.id }))
        .map { it.first }
}

fun List<Item>.continueItem(marks: List<Mark>): Item? {
    val byId = marks.associateBy { it.id }
    val ordered = seriesOrder()
    val rows = ordered.map { it.seriesEpisode(byId[it.id]) }
    val next = wp22ContinueOrdered(rows)
    return ordered.firstOrNull { it.id == next?.id }
}

fun List<Item>.nextItem(current: Item): Item? {
    val ordered = seriesOrder()
    val index = ordered.indexOfFirst { it.id == current.id }
    return if (index < 0) null else ordered.getOrNull(index + 1)
}

/** continueEpisode's tie breaks, without sorting an already ordered projection again. */
internal fun wp22ContinueOrdered(ordered: List<SeriesEpisode>): SeriesEpisode? {
    ordered.asSequence().filter { it.position > 0 && !watched(it.position, it.duration) }
        .maxWithOrNull(compareBy<SeriesEpisode> { it.updated }.thenBy { it.id })?.let { return it }
    val latest = ordered.asSequence().filter { it.position > 0 }
        .maxWithOrNull(compareBy<SeriesEpisode> { it.updated }.thenBy { it.season }.thenBy { it.episode })
    if (latest != null) {
        for (index in ordered.indexOf(latest) + 1 until ordered.size) {
            val row = ordered[index]
            if (!watched(row.position, row.duration)) return row
        }
    }
    return ordered.firstOrNull { !watched(it.position, it.duration) } ?: ordered.firstOrNull()
}
