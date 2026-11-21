package dev.lumenara.data

import androidx.sqlite.db.SimpleSQLiteQuery

/** Stored normalized sort key/id order, shared by pages, numbers and neighbours. */
data class LiveCursor(val sortKey: String, val id: String)

/** A page boundary is reusable only for the catalog and visibility snapshot that produced it. */
data class LiveWindowCursor(val cursor: LiveCursor, val revision: Long, val hidden: List<String>)

private fun CatalogFilter.livePredicate(alias: String) = copy(
    kind = "LIVE", shelf = shelf.takeIf { it == "favorite" }.orEmpty(),
).predicate(alias, includeEpisodes = true)

// A source with no category cannot stream title order through the category middle column.
// Category queries use the schema 9 kind/category/title index when source is unselected.
private fun CatalogFilter.liveOrderIndex(): String = when {
    source.isNotEmpty() && category.isNotEmpty() -> "INDEXED BY index_items_source_kind_category_sortKey_id"
    category.isEmpty() && source.isNotEmpty() -> "INDEXED BY index_items_source_kind_sortKey_id"
    category.isEmpty() -> "INDEXED BY index_items_kind_sortKey_id"
    else -> "INDEXED BY index_items_kind_category_sortKey_id"
}

internal fun livePageQuery(
    filter: CatalogFilter, limit: Int, after: LiveCursor? = null, backwards: Boolean = false,
    offset: Int = 0, targetId: String? = null,
): SimpleSQLiteQuery {
    if (!filter.derived) return providerLivePageQuery(filter, limit, after, backwards, offset, targetId)
    val eligible = filter.livePredicate("i")
    val args = eligible.args.toMutableList()
    val boundary = when {
        targetId != null -> {
            val target = filter.livePredicate("t")
            args += targetId
            args.addAll(target.args)
            // Resolve the playing row in this statement: a refresh can rename/remove/hide it.
            """AND (i.sortKey,i.id)${if (backwards) "<" else ">"}(SELECT t.sortKey,t.id FROM items t
                WHERE t.id=? AND ${target.sql})"""
        }
        after != null -> {
            args.addAll(listOf(after.sortKey, after.id))
            "AND (i.sortKey,i.id)${if (backwards) "<" else ">"}(?,?)"
        }
        offset > 0 -> {
            // Random jumps/legacy callers read only a skinny cursor through OFFSET. Normal
            // scrolling supplies the preceding page's cursor and never revisits that prefix.
            val anchor = filter.livePredicate("a")
            args.addAll(anchor.args)
            args += offset - 1
            """AND (i.sortKey,i.id)>(SELECT a.sortKey,a.id FROM items a ${filter.liveOrderIndex()} WHERE ${anchor.sql}
                ORDER BY a.sortKey,a.id LIMIT 1 OFFSET ?)"""
        }
        else -> ""
    }
    args += limit
    val direction = if (backwards) "DESC" else "ASC"
    // EQP: source/kind/category (or P1's kind/category/title) equality, then title range.
    // Existing kind/title is the streaming fallback. Only equal-title ties need a temp sort.
    return SimpleSQLiteQuery("""SELECT i.* FROM items i ${filter.liveOrderIndex()} WHERE ${eligible.sql} $boundary
        ORDER BY i.sortKey $direction,i.id $direction LIMIT ?""", args.toTypedArray())
}

internal fun liveCountQuery(filter: CatalogFilter): SimpleSQLiteQuery {
    if (filter.derived && filter.query.isEmpty() && filter.shelf != "favorite") {
        val args = mutableListOf<Any>()
        val parts = mutableListOf("kind='LIVE'")
        if (filter.source.isNotEmpty()) { parts += "source=?"; args += filter.source }
        if (filter.category.isNotEmpty()) { parts += "category=?"; args += filter.category }
        if (filter.hidden.isNotEmpty()) {
            parts += "category NOT IN (${filter.hidden.joinToString { "?" }})"
            args.addAll(filter.hidden)
        }
        return SimpleSQLiteQuery("SELECT COALESCE(SUM(count),0) FROM category_counts WHERE " +
            parts.joinToString(" AND "), args.toTypedArray())
    }
    val eligible = filter.livePredicate("i")
    return SimpleSQLiteQuery("SELECT COUNT(*) FROM items i WHERE ${eligible.sql}", eligible.args.toTypedArray())
}

/** LIVE has no production versions: a covering category index avoids touching VOD rows. */
internal fun liveCategoryCountsQuery(source: String, hidden: List<String>): SimpleSQLiteQuery {
    return categoryCountsQuery(CatalogFilter(source = source, kind = "LIVE", hidden = hidden))
}

internal fun liveIndexQuery(filter: CatalogFilter, id: String): SimpleSQLiteQuery {
    val before = filter.livePredicate("i")
    val target = filter.livePredicate("t")
    val preceding = if (filter.derived) "(i.sortKey,i.id)<(t.sortKey,t.id)" else "i.rowid<t.rowid"
    return SimpleSQLiteQuery("""SELECT (SELECT COUNT(*) FROM items i WHERE ${before.sql}
        AND $preceding) FROM items t WHERE t.id=? AND ${target.sql}""",
        (before.args + id + target.args).toTypedArray())
}

internal fun liveChannelOrderQuery(filter: CatalogFilter): SimpleSQLiteQuery {
    val eligible = filter.livePredicate("i")
    // Grouping by source in the caller preserves per-source title/id numbering. Do not sort
    // by source here: that would sort the entire LIVE catalog when source is unselected.
    val index = if (filter.derived) filter.liveOrderIndex() else "NOT INDEXED"
    val order = if (filter.derived) "i.sortKey,i.id" else "i.rowid"
    return SimpleSQLiteQuery("""SELECT i.id,i.title,${if (filter.source.isEmpty()) "i.source" else "?"} AS source FROM items i $index WHERE ${eligible.sql}
        ORDER BY $order""",
        ((if (filter.source.isEmpty()) emptyList() else listOf(filter.source)) + eligible.args).toTypedArray())
}

/** Until numbering commits, every live operation uses the original provider insertion order.
 * Resolve cursor IDs in SQL so a partially filled sortKey cannot move a page boundary.
 */
private fun providerLivePageQuery(filter: CatalogFilter, limit: Int, after: LiveCursor?,
    backwards: Boolean, offset: Int, targetId: String?): SimpleSQLiteQuery {
    val base = filter.livePredicate("i")
    val args = base.args.toMutableList()
    val anchorId = targetId ?: after?.id
    val boundary = if (anchorId == null) "" else {
        val target = filter.livePredicate("t")
        args += anchorId
        args.addAll(target.args)
        "AND i.rowid${if (backwards) "<" else ">"}(SELECT t.rowid FROM items t WHERE t.id=? AND ${target.sql})"
    }
    args.addAll(listOf(limit, if (anchorId == null) offset.coerceAtLeast(0) else 0))
    return SimpleSQLiteQuery("""SELECT i.* FROM items i NOT INDEXED WHERE ${base.sql} $boundary
        ORDER BY i.rowid ${if (backwards) "DESC" else "ASC"} LIMIT ? OFFSET ?""", args.toTypedArray())
}
