package dev.lumenara.tv

import androidx.compose.runtime.*
import dev.lumenara.data.Item
import dev.lumenara.data.LiveCursor
import dev.lumenara.data.LiveWindowCursor
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

internal const val LIVE_GRID_COLUMNS = 4
internal const val LIVE_PAGE_SIZE = LIVE_GRID_COLUMNS * 7

/** Live playback keeps its filter while sharing the catalog's bounded window policy. */
@Stable
internal class LivePages(val source: String, val category: String, val query: String, val shelf: String) :
    WindowPages(LIVE_PAGE_SIZE, LIVE_GRID_COLUMNS) {
    val boundaries = mutableMapOf<Int, LiveWindowCursor>()
    override fun loaded(): List<Item> = LiveChannelGroup(super.loaded(), source, category, query, shelf)
}
/** Keep the SQL filter when playback reaches the edge of its small in-memory window. */
internal class LiveChannelGroup(private val rows: List<Item>, val source: String,
    val category: String = "", val query: String = "", val shelf: String = "") : AbstractList<Item>() {
    override val size get() = rows.size
    override fun get(index: Int) = rows[index]
}

internal suspend fun liveNeighbor(model: LibraryModel, item: Item, group: LiveChannelGroup, delta: Int): Pair<Item, List<Item>>? {
    if (delta == 0) return item to group
    val rows = model.repo.liveNeighbors(group.source, group.category, group.query, group.shelf,
        maxOf(LIVE_PAGE_SIZE, kotlin.math.abs(delta)), item.id, delta < 0).first()
    val target = rows.getOrNull(kotlin.math.abs(delta) - 1) ?: return null
    val ordered = if (delta < 0) rows.asReversed() else rows
    return target to LiveChannelGroup(ordered, group.source, group.category, group.query, group.shelf)
}

@Composable
internal fun rememberLivePages(model: LibraryModel, source: String, category: String = "",
    query: String = "", shelf: String = "", returnId: String? = null): LivePages {
    val hidden by remember(model.repo) { model.repo.hidden }.collectAsState(initial = null)
    val state = remember(source, category, query, shelf, hidden) { LivePages(source, category, query, shelf) }
    val restored = remember { booleanArrayOf(false) }
    val restore = remember(state) {
        if (hidden != null && !restored[0]) { restored[0] = true; returnId } else null
    }
    if (hidden != null) rememberWindowPagesDriver(state,
        page = { first, last ->
            var initial = true
            model.repo.liveWindow(source, category, query, shelf,
                (last - first + 1) * LIVE_PAGE_SIZE, first * LIVE_PAGE_SIZE, state.boundaries[first])
                .map { window ->
                    if (!initial) state.boundaries.clear()
                    initial = false
                    state.boundaries.entries.removeAll { (page, cursor) ->
                        page !in first..(last + 1) || cursor.revision != window.revision || cursor.hidden != window.hidden
                    }
                    for (page in first..last) {
                        val item = window.rows.getOrNull((page - first + 1) * LIVE_PAGE_SIZE - 1)
                        if (item != null) state.boundaries[page + 1] = LiveWindowCursor(
                            LiveCursor(item.sortKey, item.id), window.revision, window.hidden)
                    }
                    window.rows
                }
        },
        total = { model.repo.liveCount(source, category, query, shelf) },
        index = { restore?.let { model.repo.liveIndex(source, it, category, query, shelf) } ?: 0 })
    return state
}
