package dev.lumenara.data

import dev.lumenara.catalog.*

fun Item.seriesEpisode(mark: Mark? = null): SeriesEpisode {
    val name = episodeName(title, category)
    return SeriesEpisode(id, name.season, name.episode,
        mark?.position ?: 0, mark?.duration ?: 0, mark?.updated ?: 0)
}

fun List<Item>.seriesOrder(): List<Item> =
    map { it to it.seriesEpisode() }
        .sortedWith(compareBy({ it.second.season }, { it.second.episode }, { it.second.id }))
        .map { it.first }

fun List<Item>.continueItem(marks: List<Mark>): Item? {
    val byId = marks.associateBy { it.id }
    val next = continueEpisode(map { it.seriesEpisode(byId[it.id]) })
    return firstOrNull { it.id == next?.id }
}

fun List<Item>.nextItem(current: Item): Item? {
    val next = nextEpisode(map { it.seriesEpisode() }, current.id)
    return firstOrNull { it.id == next?.id }
}
