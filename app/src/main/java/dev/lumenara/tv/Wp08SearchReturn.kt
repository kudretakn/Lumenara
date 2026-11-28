package dev.lumenara.tv

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.semantics.SemanticsPropertyKey

/** Completed row total; tests wait for real Room state before a distant jump. */
internal val Wp08SearchResultCountKey = SemanticsPropertyKey<Int>("Wp08SearchResultCount")

/** Only navigation metadata is saved; the bounded pager reloads Items from Room. */
internal data class Wp08SearchReturn(
    val source: String,
    val query: String,
    val shelf: String,
    val itemId: String,
    val index: Int,
    val firstVisible: Int,
    val offset: Int,
    val firstShelf: Int,
    val shelfOffset: Int,
    val hidden: List<String>,
) {
    fun matches(source: String, query: String, hidden: List<String>): Boolean =
        this.source == source && this.query == query && this.hidden == hidden

    /** Preserve the item's position in the viewport if its catalog rank changed. */
    fun viewport(resolvedIndex: Int, sameItem: Boolean): Pair<Int, Int> =
        if (sameItem) (firstVisible + resolvedIndex - index).coerceAtLeast(0) to offset
        else 0 to 0

    companion object {
        val Saver = listSaver<Wp08SearchReturn?, Any>(
            save = { target -> target?.let { listOf(it.source, it.query, it.shelf, it.itemId, it.index,
                it.firstVisible, it.offset, it.firstShelf, it.shelfOffset) + it.hidden } ?: emptyList() },
            restore = { if (it.isEmpty()) null else Wp08SearchReturn(it[0] as String, it[1] as String, it[2] as String,
                it[3] as String, it[4] as Int, it[5] as Int, it[6] as Int,
                it[7] as Int, it[8] as Int, it.drop(9).map { value -> value as String }) },
        )
    }
}
