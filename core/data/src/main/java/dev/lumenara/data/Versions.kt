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

/** Group before the visible limit; provider rows and the Room v7 schema remain untouched. */
fun List<Item>.versionGroups(): List<List<Item>> {
    val groups = linkedMapOf<String, MutableList<Item>>()
    groupBy { if (it.kind in listOf("MOVIE", "SERIES")) it.preferenceIdentity() else it.id }
        .forEach { (titleKey, rows) ->
            val ids = rows.map { it.tmdbId() }.filter { it.isNotBlank() }.distinct()
            rows.forEach { row ->
                val key = if (row.kind !in listOf("MOVIE", "SERIES")) row.id
                    else if (ids.size <= 1) ids.firstOrNull()?.let { "${row.source}|${row.kind}|tmdb:$it" } ?: titleKey
                    else row.tmdbId().takeIf { it.isNotBlank() }?.let { "${row.source}|${row.kind}|tmdb:$it" } ?: row.id
                groups.getOrPut(key) { mutableListOf() }.add(row)
            }
        }
    return groups.values.toList()
}

fun List<Item>.mergeVersions(): List<Item> = versionGroups().map { versions ->
        val representative = versions.minWith(compareBy<Item> {
            when (productionName(it.title, it.year).version) {
                AudioVersion.DUBBED -> 0
                AudioVersion.ORIGINAL -> 1
                AudioVersion.SUBTITLED -> 2
            }
        }.thenBy { it.id })
        if (representative.kind in listOf("MOVIE", "SERIES")) {
            val name = productionName(representative.title, representative.year)
            representative.copy(title = name.title, year = name.year)
        } else representative
    }

