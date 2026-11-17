package dev.lumenara.data

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.lumenara.catalog.productionName
import dev.lumenara.catalog.searchable

/** Partial updates preserve URLs, provider metadata and all user records. */
data class TmdbAssignment(val id: String, val search: String, val year: String)

private fun Item.titleBucket() = Triple(source, kind, searchable(productionName(title, year).title))

/** Probe only the affected title buckets (all years), old groups and explicit TMDB siblings. */
internal fun relatedVersionsQuery(rows: List<Item>): SimpleSQLiteQuery {
    require(rows.isNotEmpty() && rows.map { it.source to it.kind }.distinct().size == 1)
    val args = mutableListOf<Any>(rows.first().source, rows.first().kind)
    val probes = mutableListOf<String>()
    fun probe(sql: String, value: String) { probes += sql; args += value }
    rows.map { it.versionKey }.filter(String::isNotEmpty).distinct().forEach {
        probe("versionKey=?", it)
    }
    rows.map(Item::tmdbId).filter(String::isNotEmpty).distinct().forEach {
        probe("search LIKE ? ESCAPE '\\'", "%[tmdb:$it]%")
    }
    rows.map { it.titleBucket().third }.filter(String::isNotEmpty).distinct().forEach { title ->
        // Folded provider search text retains the words even with quality/language tags between
        // them. Filter the SQL superset by the complete normalized title before accepting rows.
        val words = title.split(Regex("\\s+")).map {
            it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        }
        probe("search LIKE ? ESCAPE '\\'", words.joinToString("%", "%", "%"))
    }
    return SimpleSQLiteQuery("SELECT * FROM items WHERE source=? AND kind=? AND (" +
        probes.ifEmpty { listOf("0") }.joinToString(" OR ") + ")", args.toTypedArray())
}

/** Called off the main thread, under the repository import lock and one Room transaction. */
internal class VersionRefresher(
    private val seeds: suspend (List<String>) -> List<Item>,
    private val related: suspend (List<Item>) -> List<Item>,
    private val cached: (Item) -> TmdbMatch?,
    private val persist: suspend (List<TmdbAssignment>, List<VersionAssignment>) -> Unit,
) {
    suspend fun refresh(ids: List<String>) {
        val original = linkedMapOf<String, Item>()
        val resolved = linkedMapOf<String, Item>()
        fun accept(row: Item) {
            if (row.id !in original) {
                original[row.id] = row
                resolved[row.id] = row.withTmdbMatch(cached(row))
            }
        }
        seeds(ids).forEach(::accept)
        var pending = resolved.values.toList()
        while (pending.isNotEmpty()) {
            val before = resolved.keys.toSet()
            pending.groupBy { it.source to it.kind }.values.forEach { group ->
                group.chunked(32).forEach { batch ->
                    val titles = batch.map { it.titleBucket() }.toSet()
                    val keys = batch.map { it.versionKey }.filter(String::isNotEmpty).toSet()
                    val tmdb = batch.map(Item::tmdbId).filter(String::isNotEmpty).toSet()
                    related(batch).filter {
                        it.titleBucket() in titles || it.versionKey.isNotEmpty() && it.versionKey in keys ||
                            it.tmdbId().isNotEmpty() && it.tmdbId() in tmdb
                    }.forEach(::accept)
                }
            }
            pending = resolved.filterKeys { it !in before }.values.toList()
        }
        val matches = resolved.values.filter {
            val old = original.getValue(it.id)
            it.search != old.search || it.year != old.year
        }.map { TmdbAssignment(it.id, it.search, it.year) }
        val assignments = resolved.values.toList().versionAssignments().filter {
            val old = original.getValue(it.id)
            it.versionKey != old.versionKey || it.isRepresentative != old.isRepresentative ||
                it.displayTitle != old.displayTitle || it.displayYear != old.displayYear || it.versionRank != old.versionRank
        }
        if (matches.isNotEmpty() || assignments.isNotEmpty()) persist(matches, assignments)
    }
}
