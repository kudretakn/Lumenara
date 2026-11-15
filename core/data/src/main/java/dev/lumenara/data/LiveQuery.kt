package dev.lumenara.data

import androidx.sqlite.db.SimpleSQLiteQuery

/** BINARY title/id order, shared by paging, channel numbers and playback neighbours. */
data class LiveCursor(val title: String, val id: String)

/** A page boundary is reusable only for the catalog and visibility snapshot that produced it. */
data class LiveWindowCursor(val cursor: LiveCursor, val revision: Long, val hidden: List<String>)

private fun CatalogFilter.livePredicate(alias: String) = copy(
    kind = "LIVE", shelf = shelf.takeIf { it == "favorite" }.orEmpty(),
).predicate(alias, includeEpisodes = true)

// A source with no category cannot stream title order through the category middle column.
// Keep this fallback available on schema 8; category queries can use P1's schema 9 index.
private fun CatalogFilter.liveOrderIndex(): String = when {
    source.isNotEmpty() && category.isNotEmpty() -> "INDEXED BY index_items_source_kind_category_title"
    category.isEmpty() -> "INDEXED BY index_items_kind_title"
    else -> ""
}

internal fun livePageQuery(
    filter: CatalogFilter, limit: Int, after: LiveCursor? = null, backwards: Boolean = false,
    offset: Int = 0, targetId: String? = null,
): SimpleSQLiteQuery {
    val eligible = filter.livePredicate("i")
    val args = eligible.args.toMutableList()
    val boundary = when {
        targetId != null -> {
            val target = filter.livePredicate("t")
            args += targetId
            args.addAll(target.args)
            // Resolve the playing row in this statement: a refresh can rename/remove/hide it.
            """AND (i.title,i.id)${if (backwards) "<" else ">"}(SELECT t.title,t.id FROM items t
                WHERE t.id=? AND ${target.sql})"""
        }
        after != null -> {
            args.addAll(listOf(after.title, after.id))
            "AND (i.title,i.id)${if (backwards) "<" else ">"}(?,?)"
        }
        offset > 0 -> {
            // Random jumps/legacy callers read only a skinny cursor through OFFSET. Normal
            // scrolling supplies the preceding page's cursor and never revisits that prefix.
            val anchor = filter.livePredicate("a")
            args.addAll(anchor.args)
            args += offset - 1
            """AND (i.title,i.id)>(SELECT a.title,a.id FROM items a ${filter.liveOrderIndex()} WHERE ${anchor.sql}
                ORDER BY a.title,a.id LIMIT 1 OFFSET ?)"""
        }
        else -> ""
    }
    args += limit
    val direction = if (backwards) "DESC" else "ASC"
    // EQP: source/kind/category (or P1's kind/category/title) equality, then title range.
    // Existing kind/title is the streaming fallback. Only equal-title ties need a temp sort.
    return SimpleSQLiteQuery("""SELECT i.* FROM items i ${filter.liveOrderIndex()} WHERE ${eligible.sql} $boundary
        ORDER BY i.title $direction,i.id $direction LIMIT ?""", args.toTypedArray())
}

internal fun liveCountQuery(filter: CatalogFilter): SimpleSQLiteQuery {
    val eligible = filter.livePredicate("i")
    return SimpleSQLiteQuery("SELECT COUNT(*) FROM items i WHERE ${eligible.sql}", eligible.args.toTypedArray())
}

internal fun liveIndexQuery(filter: CatalogFilter, id: String): SimpleSQLiteQuery {
    val before = filter.livePredicate("i")
    val target = filter.livePredicate("t")
    return SimpleSQLiteQuery("""SELECT (SELECT COUNT(*) FROM items i WHERE ${before.sql}
        AND (i.title,i.id)<(t.title,t.id)) FROM items t WHERE t.id=? AND ${target.sql}""",
        (before.args + id + target.args).toTypedArray())
}

internal fun liveChannelOrderQuery(filter: CatalogFilter): SimpleSQLiteQuery {
    val eligible = filter.livePredicate("i")
    // Grouping by source in the caller preserves per-source title/id numbering. Do not sort
    // by source here: that would sort the entire LIVE catalog when source is unselected.
    return SimpleSQLiteQuery("""SELECT i.id,i.title,${if (filter.source.isEmpty()) "i.source" else "?"} AS source FROM items i ${filter.liveOrderIndex()} WHERE ${eligible.sql}
        ORDER BY i.title,i.id""",
        ((if (filter.source.isEmpty()) emptyList() else listOf(filter.source)) + eligible.args).toTypedArray())
}

