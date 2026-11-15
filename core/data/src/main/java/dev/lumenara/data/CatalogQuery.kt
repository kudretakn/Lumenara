package dev.lumenara.data

import androidx.sqlite.db.SimpleSQLiteQuery

internal data class CatalogPredicate(val sql: String, val args: List<Any> = emptyList())

/** Bound parameters only; optional predicates and CASE sorting must not obscure index prefixes. */
internal data class CatalogFilter(
    val source: String = "", val kind: String = "", val category: String = "",
    val query: String = "", val shelf: String = "", val year: String = "",
    val genre: String = "", val language: String = "", val minRating: Double? = null,
    val hidden: List<String> = emptyList(),
) {
    fun predicate(alias: String, includeEpisodes: Boolean = false): CatalogPredicate {
        val parts = mutableListOf("1=1")
        if (!includeEpisodes) parts += "$alias.kind!='EPISODE'"
        val args = mutableListOf<Any>()
        fun add(sql: String, value: Any) { parts += sql; args += value }
        if (source.isNotEmpty()) add("$alias.source=?", source)
        if (kind.isNotEmpty()) add("$alias.kind=?", kind)
        if (category.isNotEmpty()) add("$alias.category=?", category)
        if (query.isNotEmpty()) add("instr($alias.search,?)>0", query)
        if (year.isNotEmpty()) add("$alias.year=?", year)
        if (genre.isNotEmpty()) add("instr($alias.genre,?)>0", genre)
        if (language.isNotEmpty()) add("$alias.language=?", language)
        if (minRating != null) add("CAST($alias.rating AS REAL)>=?", minRating)
        if (hidden.isNotEmpty()) {
            parts += "$alias.category NOT IN (${hidden.joinToString { "?" }})"
            args.addAll(hidden)
        }
        when (shelf) {
            "favorite" -> parts += "EXISTS(SELECT 1 FROM marks WHERE id=$alias.id AND favorite=1)"
            "list" -> parts += "EXISTS(SELECT 1 FROM marks WHERE id=$alias.id AND listed=1)"
            "resume" -> parts += "EXISTS(SELECT 1 FROM marks WHERE id=$alias.id AND position>0 AND (duration<=0 OR position<duration*0.95))"
            "popular" -> parts += "CAST($alias.rating AS REAL)>0"
        }
        return CatalogPredicate(parts.joinToString(" AND "), args)
    }

    // Even the default >=0 rating predicate can reject a provider's negative-rated winner.
    // It must permit an eligible sibling, just like a nonzero rating threshold.
    val filtersVersions: Boolean get() = category.isNotEmpty() || query.isNotEmpty() || year.isNotEmpty() ||
        genre.isNotEmpty() || language.isNotEmpty() || minRating != null || hidden.isNotEmpty() ||
        shelf in listOf("favorite", "list", "resume", "popular")
}

/** The import winner is constant-time; fallback only probes this production's indexed siblings.
 * This preserves filtering before merging, e.g. a safe subtitle row with a hidden dubbed row.
 * Empty versionKey means a legacy row, which must stay independent until refreshed.
 */
internal fun representativePredicate(
    eligible: (String) -> CatalogPredicate,
    filtered: Boolean,
    sameCategory: Boolean = false,
): CatalogPredicate {
    if (!filtered) return CatalogPredicate("i.isRepresentative=1")
    val candidate = eligible("v")
    return CatalogPredicate("""(i.isRepresentative=1 OR (i.versionKey!='' AND NOT EXISTS(
        SELECT 1 FROM items v INDEXED BY index_items_versionKey
        WHERE v.versionKey=i.versionKey AND v.versionRank<i.versionRank
        ${if (sameCategory) "AND v.category=i.category" else ""} AND ${candidate.sql})))""", candidate.args)
}

internal fun browseQuery(filter: CatalogFilter, limit: Int): SimpleSQLiteQuery {
    val eligible = filter.predicate("i")
    val representative = representativePredicate({ filter.predicate(it) }, filter.filtersVersions)
    // EQP: equality on kind/category -> index_items_kind_category_title (all sources), or
    // source/kind/category -> index_items_source_kind_category_title; otherwise
    // kind/isRepresentative -> index_items_kind_isRepresentative_title. Filtered all-category
    // queries use kind/title because fallback cannot constrain isRepresentative. Equal-title id
    // ordering may use a small right-part temp sort, not a sort of the whole catalog.
    // instr(search, ?) has no index: evaluate during this scan and stop at the visible LIMIT.
    val dedupLive = filter.source.isEmpty() && filter.kind in listOf("", "LIVE") &&
        filter.shelf !in listOf("resume", "favorite", "list")
    // Preserve the previous provider-ID/title/tvgId identity, solely for multi-source LIVE.
    val grouping = if (dedupLive) """GROUP BY CASE WHEN i.tvgId!=''
        THEN substr(i.id,instr(i.id,':')+1) ELSE i.id END,i.tvgId,i.title,i.kind
        HAVING i.id=MIN(i.id)""" else ""
    val order = when (filter.shelf) {
        "resume" -> "m.updated DESC,"
        "recent" -> "i.rowid DESC,"
        "popular" -> "CAST(i.rating AS REAL) DESC,"
        else -> ""
    }
    // With source=? and a filtered representative OR, older SQLite versions can prefer the
    // source/category index then sort every category. Pin the streaming index for title grids.
    val index = if (filter.shelf == "recent") "NOT INDEXED" // reverse rowid scan stops at LIMIT
        else if (order.isEmpty() && filter.kind.isNotEmpty()) "INDEXED BY " + when {
        filter.source.isNotEmpty() && filter.category.isNotEmpty() -> "index_items_source_kind_category_title"
        filter.category.isNotEmpty() -> "index_items_kind_category_title"
        filter.filtersVersions -> "index_items_kind_title"
        else -> "index_items_kind_isRepresentative_title"
    } else ""
    val args = eligible.args + representative.args
    if (dedupLive && filter.kind.isEmpty()) {
        // Mixed search: group only the LIVE branch. Movie/series rows never enter a GROUP BY.
        return SimpleSQLiteQuery("""SELECT i.* FROM (
            SELECT i.*,i.rowid AS catalogRow FROM items i
            WHERE ${eligible.sql} AND ${representative.sql} AND i.kind!='LIVE'
            UNION ALL
            SELECT i.*,i.rowid AS catalogRow FROM items i
            WHERE ${eligible.sql} AND ${representative.sql} AND i.kind='LIVE' $grouping
            ) i ORDER BY ${order.replace("i.rowid", "i.catalogRow")}i.title,i.id LIMIT ?""",
            (args + args + limit).toTypedArray())
    }
    return SimpleSQLiteQuery("""SELECT i.* FROM items i $index
        ${if (filter.shelf == "resume") "LEFT JOIN marks m ON m.id=i.id" else ""}
        WHERE ${eligible.sql} AND ${representative.sql} $grouping
        ORDER BY ${order}i.title,i.id LIMIT ?""",
        (args + limit).toTypedArray())
}

internal fun categoryCountsQuery(filter: CatalogFilter, categoriesOnly: Boolean = false): SimpleSQLiteQuery {
    val eligible = filter.predicate("i", includeEpisodes = true)
    // A production may have versions in multiple provider categories. Count it once in EACH
    // category, choosing that category's first eligible version, just as browsing it does.
    val representative = representativePredicate({ filter.predicate(it, includeEpisodes = true) }, true, sameCategory = true)
    val select = if (categoriesOnly) "SELECT DISTINCT i.category" else "SELECT i.category,COUNT(*) AS count"
    // Fixed kind leaves category ordered, even when source="". No whole-table scan or GROUP BY
    // temp tree; a source-specific request uses the existing source/kind/category/title index.
    val index = if (filter.kind.isEmpty()) "" else "INDEXED BY " +
        if (filter.source.isEmpty()) "index_items_kind_category_title" else "index_items_source_kind_category_title"
    return SimpleSQLiteQuery("""$select FROM items i $index WHERE ${eligible.sql} AND ${representative.sql}
        ${if (categoriesOnly) "" else "GROUP BY i.category"} ORDER BY i.category""",
        (eligible.args + representative.args).toTypedArray())
}

/** One ordered pass supplies the three Hub totals and their category screens, leaving room for
 * Hub's two history shelves within the four-statement startup budget (names + counts + shelves).
 */
internal fun sourceCategoryCountsQuery(source: String, hidden: List<String>): SimpleSQLiteQuery {
    val filter = CatalogFilter(source = source, hidden = hidden)
    val eligible = filter.predicate("i", includeEpisodes = true)
    val representative = representativePredicate({ alias ->
        val candidate = filter.predicate(alias, includeEpisodes = true)
        CatalogPredicate("${candidate.sql} AND $alias.kind=i.kind", candidate.args)
    }, true, sameCategory = true)
    val index = if (source.isEmpty()) "index_items_kind_category_title" else "index_items_source_kind_category_title"
    // kind IN probes are ordered by kind/category in either index, so grouping needs no temp tree.
    return SimpleSQLiteQuery("""SELECT i.kind,i.category,COUNT(*) AS count FROM items i INDEXED BY $index
        WHERE i.kind IN ('LIVE','MOVIE','SERIES') AND ${eligible.sql} AND ${representative.sql}
        GROUP BY i.kind,i.category ORDER BY i.kind,i.category""",
        (eligible.args + representative.args).toTypedArray())
}

internal fun shelfCountQuery(filter: CatalogFilter): SimpleSQLiteQuery {
    val eligible = filter.predicate("i")
    val representative = representativePredicate({ filter.predicate(it) }, filter.filtersVersions)
    return SimpleSQLiteQuery("SELECT COUNT(*) FROM items i WHERE ${eligible.sql} AND ${representative.sql}",
        (eligible.args + representative.args).toTypedArray())
}

/** Bounded legacy shelves share the same filter-before-representative policy as the grid. */
internal fun shelfRowsQuery(
    filter: CatalogFilter, limit: Int, order: String, includeEpisodes: Boolean = false,
    extra: (String) -> CatalogPredicate,
): SimpleSQLiteQuery {
    fun eligible(alias: String): CatalogPredicate {
        val base = filter.predicate(alias, includeEpisodes)
        val more = extra(alias)
        return CatalogPredicate("${base.sql} AND (${more.sql})", base.args + more.args)
    }
    val base = eligible("i")
    val representative = representativePredicate(::eligible, true)
    // Home's newest-row shelf can stop after six eligible cards in reverse rowid order.
    val index = if (order == "i.rowid DESC") "NOT INDEXED" else ""
    return SimpleSQLiteQuery("""SELECT i.* FROM items i $index WHERE ${base.sql} AND ${representative.sql}
        ORDER BY $order LIMIT ?""", (base.args + representative.args + limit).toTypedArray())
}
