package dev.lumenara.data

import androidx.sqlite.db.SimpleSQLiteQuery

internal data class CatalogPredicate(val sql: String, val args: List<Any> = emptyList())

/** Bound parameters only; optional predicates and CASE sorting must not obscure index prefixes. */
internal data class CatalogFilter(
    val source: String = "", val kind: String = "", val category: String = "",
    val query: String = "", val shelf: String = "", val year: String = "",
    val genre: String = "", val language: String = "", val minRating: Double? = null,
    val hidden: List<String> = emptyList(),
    val derived: Boolean = true,
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
 * Legacy and not-yet-applied rows stay independent until their batch commits.
 */
internal fun representativePredicate(
    eligible: (String) -> CatalogPredicate,
    filtered: Boolean,
    sameCategory: Boolean = false,
): CatalogPredicate {
    val pending = pendingVersionPredicate("i")
    val legacy = "(COALESCE(i.versionKey,'')='' OR $pending)"
    if (!filtered) return CatalogPredicate("(i.isRepresentative=1 OR $legacy)")
    val candidate = eligible("v")
    return CatalogPredicate("""(i.isRepresentative=1 OR $legacy OR (i.versionKey!='' AND NOT EXISTS(
        SELECT 1 FROM items v INDEXED BY index_items_versionKey
        WHERE v.versionKey=i.versionKey AND v.versionRank<i.versionRank
        AND NOT ${pendingVersionPredicate("v")}
        ${if (sameCategory) "AND v.category=i.category" else ""} AND ${candidate.sql})))""", candidate.args)
}

// A single primary-key state probe; unfinished legacy keys must never hide another card.
private fun pendingVersionPredicate(alias: String): String = """EXISTS(
    SELECT 1 FROM version_backfill_state b WHERE b.id=1
    AND (b.phase IN ('normalize','resolve') OR (b.phase='apply' AND $alias.id>b.cursor)))"""

private fun browseStatement(filter: CatalogFilter, ordered: Boolean = true, includeSort: Boolean = false,
    projectionOnly: String? = null): CatalogPredicate {
    val sort = if (filter.derived) "sortKey" else "title"
    val eligible = filter.predicate("i")
    val representative = representativePredicate({ filter.predicate(it) }, filter.filtersVersions)
    // EQP: equality on kind/category -> index_items_kind_category_sortKey_id (all sources), or
    // source/kind/category -> index_items_source_kind_category_sortKey_id; otherwise kind/title.
    // The legacy fallback cannot constrain isRepresentative. Equal-title id
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
        filter.source.isNotEmpty() && filter.category.isNotEmpty() -> "index_items_source_kind_category_sortKey_id"
        filter.category.isNotEmpty() -> "index_items_kind_category_sortKey_id"
        // Pending legacy rows can have isRepresentative=0, so that column is no longer
        // an equality constraint. Keep title order streaming throughout the backfill.
        filter.source.isNotEmpty() -> "index_items_source_kind_sortKey_id"
        else -> "index_items_kind_sortKey_id"
    } else ""
    val orderIndex = if (filter.derived) index else index
        .replace("source_kind_sortKey_id", "kind_title").replace("sortKey_id", "title")
    val args = eligible.args + representative.args
    fun projection(row: String) = projectionOnly ?: if (!includeSort) "i.*" else "i.*," + when (filter.shelf) {
        "resume" -> "m.updated"
        "recent" -> row
        "popular" -> "CAST(i.rating AS REAL)"
        else -> "0"
    } + " AS catalogSort"
    if (dedupLive && filter.kind.isEmpty()) {
        // Mixed search: group only the LIVE branch. Movie/series rows never enter a GROUP BY.
        return CatalogPredicate("""SELECT ${projection("i.catalogRow")} FROM (
            SELECT i.*,i.rowid AS catalogRow FROM items i
            WHERE ${eligible.sql} AND ${representative.sql} AND i.kind!='LIVE'
            UNION ALL
            SELECT i.*,i.rowid AS catalogRow FROM items i
            WHERE ${eligible.sql} AND ${representative.sql} AND i.kind='LIVE' $grouping
            ) i ${if (ordered) "ORDER BY ${order.replace("i.rowid", "i.catalogRow")}i.$sort,i.id" else ""}""",
            args + args)
    }
    return CatalogPredicate("""SELECT ${projection("i.rowid")} FROM items i $orderIndex
        ${if (filter.shelf == "resume") "LEFT JOIN marks m ON m.id=i.id" else ""}
        WHERE ${eligible.sql} AND ${representative.sql} $grouping
        ${if (ordered) "ORDER BY ${order}i.$sort,i.id" else ""}""", args)
}

/** The same indexed statement for a bounded page, without allocating preceding Item rows. */
internal fun browseQuery(filter: CatalogFilter, limit: Int, offset: Int = 0): SimpleSQLiteQuery {
    val statement = browseStatement(filter)
    return SimpleSQLiteQuery("${statement.sql} LIMIT ? OFFSET ?",
        (statement.args + limit + offset.coerceAtLeast(0)).toTypedArray())
}

internal fun browseCountQuery(filter: CatalogFilter): SimpleSQLiteQuery {
    val statement = browseStatement(filter, ordered = false)
    return SimpleSQLiteQuery("SELECT COUNT(*) FROM (${statement.sql})", statement.args.toTypedArray())
}

/** Only mixed poster/channel grids need this small span index; no artwork/Item is retained. */
internal fun browseKindsQuery(filter: CatalogFilter): SimpleSQLiteQuery {
    val statement = browseStatement(filter, projectionOnly = "i.kind")
    return SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray())
}

/** Resolve a saved focus without allocating a full catalog, preserving shelf tie order. */
internal fun browseIndexQuery(filter: CatalogFilter, id: String): SimpleSQLiteQuery {
    val statement = browseStatement(filter, ordered = false, includeSort = true)
    val sort = if (filter.derived) "sortKey" else "title"
    val before = if (filter.shelf in listOf("resume", "recent", "popular"))
        "(i.catalogSort>t.catalogSort OR (i.catalogSort=t.catalogSort AND (i.$sort,i.id)<(t.$sort,t.id)))"
        else "(i.$sort,i.id)<(t.$sort,t.id)"
    return SimpleSQLiteQuery("""SELECT (SELECT COUNT(*) FROM (${statement.sql}) i WHERE $before)
        FROM (${statement.sql}) t WHERE t.id=? LIMIT 1""", (statement.args + statement.args + id).toTypedArray())
}

/** Upgrade-only aggregation over original columns; never reads partially published counts. */
internal fun fallbackCategoryCountsQuery(filter: CatalogFilter): SimpleSQLiteQuery {
    val base = filter.predicate("i")
    val representative = representativePredicate({ filter.predicate(it) }, true, sameCategory = true)
    return SimpleSQLiteQuery("""SELECT i.kind,i.category,COUNT(*) AS count,'' AS searchKey FROM items i
        WHERE i.kind IN ('LIVE','MOVIE','SERIES') AND ${base.sql} AND ${representative.sql}
        GROUP BY i.kind,i.category""",
        (base.args + representative.args).toTypedArray())
}

internal fun fallbackHubTotalsQuery(source: String, hidden: List<String>): SimpleSQLiteQuery {
    val filter = CatalogFilter(source = source, hidden = hidden)
    val base = filter.predicate("i")
    val representative = representativePredicate({ filter.predicate(it) }, filter.filtersVersions)
    return SimpleSQLiteQuery("""SELECT i.kind,COUNT(*) AS count FROM items i
        WHERE i.kind IN ('LIVE','MOVIE','SERIES') AND ${base.sql} AND ${representative.sql}
        GROUP BY i.kind ORDER BY i.kind""", (base.args + representative.args).toTypedArray())
}

internal fun categoryCountsQuery(filter: CatalogFilter, categoriesOnly: Boolean = false): SimpleSQLiteQuery {
    val parts = mutableListOf("1=1")
    val args = mutableListOf<Any>()
    if (filter.source.isNotEmpty()) { parts += "source=?"; args += filter.source }
    if (filter.kind.isNotEmpty()) { parts += "kind=?"; args += filter.kind }
    if (filter.hidden.isNotEmpty()) {
        parts += "category NOT IN (${filter.hidden.joinToString { "?" }})"
        args.addAll(filter.hidden)
    }
    val select = if (categoriesOnly) "category" else "category,SUM(count) AS count,MIN(searchKey) AS searchKey"
    return SimpleSQLiteQuery("SELECT $select FROM category_counts WHERE ${parts.joinToString(" AND ")} " +
        "GROUP BY category ORDER BY MIN(sortOrder),category", args.toTypedArray())
}

internal fun sourceCategoryCountsQuery(source: String, hidden: List<String>): SimpleSQLiteQuery {
    val args = mutableListOf<Any>()
    val parts = mutableListOf("1=1")
    if (source.isNotEmpty()) { parts += "source=?"; args += source }
    if (hidden.isNotEmpty()) {
        parts += "category NOT IN (${hidden.joinToString { "?" }})"
        args.addAll(hidden)
    }
    return SimpleSQLiteQuery("SELECT kind,category,SUM(count) AS count,MIN(searchKey) AS searchKey FROM category_counts WHERE " +
        parts.joinToString(" AND ") + " GROUP BY kind,category ORDER BY kind,MIN(sortOrder),category", args.toTypedArray())
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
