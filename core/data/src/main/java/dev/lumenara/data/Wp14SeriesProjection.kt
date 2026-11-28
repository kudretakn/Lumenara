package dev.lumenara.data

import dev.lumenara.catalog.EpisodeName
import dev.lumenara.catalog.SeriesEpisode
import dev.lumenara.catalog.continueEpisode
import dev.lumenara.catalog.displayTitle
import dev.lumenara.catalog.episodeName
import dev.lumenara.catalog.watched
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Temporary in-memory projection until persisted series projections are introduced. */
data class Wp14SeriesCatalog(
    val items: List<Item> = emptyList(),
    val names: Map<String, EpisodeName> = emptyMap(),
    val titles: Map<String, String> = emptyMap(),
    val seasons: Map<Int, List<Item>> = emptyMap(),
    val indices: Map<String, Int> = emptyMap(),
) {
    fun neighbor(id: String, delta: Int): Item? = indices[id]?.let { items.getOrNull(it + delta) }
}

data class Wp14SeriesProgress(
    val marks: Map<String, Mark> = emptyMap(),
    val continuing: Item? = null,
    val hasHistory: Boolean = false,
    val completedSeasons: Set<Int> = emptySet(),
)

suspend fun wp14SeriesCatalog(items: List<Item>): Wp14SeriesCatalog = withContext(Dispatchers.Default) {
    val names = items.associate { it.id to episodeName(it.title, it.category) }
    val ordered = items.sortedWith(compareBy({ names.getValue(it.id).season },
        { names.getValue(it.id).episode }, { it.id }))
    Wp14SeriesCatalog(ordered, names, names.mapValues { it.value.title.displayTitle() },
        ordered.groupBy { names.getValue(it.id).season },
        ordered.mapIndexed { index, item -> item.id to index }.toMap())
}

suspend fun wp14SeriesProgress(catalog: Wp14SeriesCatalog, marks: List<Mark>): Wp14SeriesProgress =
    withContext(Dispatchers.Default) {
        val byId = marks.associateBy { it.id }
        val continuing = continueEpisode(catalog.items.map { item ->
            val name = catalog.names.getValue(item.id)
            val mark = byId[item.id]
            SeriesEpisode(item.id, name.season, name.episode,
                mark?.position ?: 0, mark?.duration ?: 0, mark?.updated ?: 0)
        })
        Wp14SeriesProgress(byId, continuing?.id?.let { catalog.indices[it] }?.let { catalog.items[it] },
            marks.any { it.position > 0 }, catalog.seasons.filterValues { rows ->
                rows.all { item -> byId[item.id]?.let { watched(it.position, it.duration) } == true }
            }.keys.toSet())
    }
