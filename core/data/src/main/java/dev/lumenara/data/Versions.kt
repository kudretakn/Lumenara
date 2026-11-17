package dev.lumenara.data

import dev.lumenara.catalog.*

private val tmdbMarker = Regex("\\[tmdb:([0-9]+)\\]")

fun Item.tmdbId(): String = tmdbMarker.find(search)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.toString().orEmpty()
fun Item.productionKey(): String = "$source|$kind|${productionName(title, year).key(tmdbId())}"
// The preference identity stays stable when get_vod_info later supplies a previously unknown ID.
fun Item.preferenceIdentity(): String = "$source|$kind|${productionName(title, year).key()}"
fun Item.sameProduction(other: Item): Boolean = source == other.source && kind == other.kind &&
    if (tmdbId().isNotBlank() && other.tmdbId().isNotBlank()) tmdbId() == other.tmdbId()
    else productionName(title, year).key() == productionName(other.title, other.year).key()

/** Resolve missing IDs within a title/year bucket before joining translated titles by TMDB. */
private fun List<Item>.keyedVersionGroups(): Map<String, List<Item>> {
    val groups = linkedMapOf<String, MutableList<Item>>()
    val names = associate { it.id to productionName(it.title, it.year) }
    // Providers often put the year on only one rendition. Infer it only in an unambiguous
    // source/kind/title bucket; two dated remakes must never absorb an undated third row.
    val inferredYears = mutableMapOf<String, String>()
    filter { it.kind in listOf("MOVIE", "SERIES") }
        .groupBy { "${it.source}|${it.kind}|${searchable(names.getValue(it.id).title)}" }
        .values.forEach { rows ->
            val years = rows.map { names.getValue(it.id).year }.filter(String::isNotEmpty).distinct()
            if (years.size == 1) rows.forEach { inferredYears[it.id] = years.single() }
        }
    groupBy {
        val name = names.getValue(it.id)
        if (it.kind in listOf("MOVIE", "SERIES"))
            "${it.source}|${it.kind}|${name.copy(year = name.year.ifEmpty { inferredYears[it.id].orEmpty() }).key()}"
        else it.id
    }
        .forEach { (titleKey, rows) ->
            val ids = rows.map { it.tmdbId() }.filter { it.isNotBlank() }.distinct()
            rows.forEach { row ->
                val key = if (row.kind !in listOf("MOVIE", "SERIES")) row.id
                    else if (ids.size <= 1) ids.firstOrNull()?.let { "${row.source}|${row.kind}|tmdb:$it" } ?: titleKey
                    else row.tmdbId().takeIf { it.isNotBlank() }?.let { "${row.source}|${row.kind}|tmdb:$it" } ?: row.id
                groups.getOrPut(key) { mutableListOf() }.add(row)
            }
        }
    return groups
}

fun List<Item>.versionGroups(): List<List<Item>> = keyedVersionGroups().values.toList()

/** Exact stream choices distinguish HD/4K with the same audio; retain old audio preferences. */
internal fun List<Item>.preferredVersion(preferred: String, base: Item): Item =
    firstOrNull { "id:${it.id}" == preferred }
        ?: firstOrNull { productionName(it.title, it.year).version.name == preferred }
        ?: firstOrNull { productionName(it.title, it.year).version == AudioVersion.DUBBED }
        ?: firstOrNull { it.id == base.id } ?: base

private val versionOrder = compareBy<Item> {
            when (productionName(it.title, it.year).version) {
                AudioVersion.DUBBED -> 0
                AudioVersion.ORIGINAL -> 1
                AudioVersion.SUBTITLED -> 2
            }
        }.thenBy { it.id }

/** Partial Room updates avoid writing sealed URLs and other provider metadata a second time. */
data class VersionAssignment(
    val id: String,
    val versionKey: String,
    val isRepresentative: Boolean,
    val displayTitle: String,
    val displayYear: String,
    val versionRank: Int,
)

fun List<Item>.versionAssignments(): List<VersionAssignment> = keyedVersionGroups().flatMap { (key, rows) ->
    val years = rows.map { productionName(it.title, it.year).year }.filter(String::isNotEmpty).distinct()
    rows.sortedWith(versionOrder).mapIndexed { rank, row ->
        val name = productionName(row.title, row.year)
        VersionAssignment(row.id, key, rank == 0,
            if (row.kind in listOf("MOVIE", "SERIES")) name.title else row.title,
            if (row.kind in listOf("MOVIE", "SERIES")) name.year.ifEmpty { years.singleOrNull().orEmpty() } else row.year, rank)
    }
}

/** Only bounded query results need a display projection; grouping is done during import. */
fun Item.displayVersion(): Item = if (versionKey.isEmpty() || displayTitle.isEmpty()) this
    else copy(title = displayTitle, year = displayYear)

fun List<Item>.mergeVersions(): List<Item> = versionGroups().map { versions ->
        val representative = versions.minWith(versionOrder)
        if (representative.kind in listOf("MOVIE", "SERIES")) {
            val name = productionName(representative.title, representative.year)
            representative.copy(title = name.title, year = name.year)
        } else representative
    }

