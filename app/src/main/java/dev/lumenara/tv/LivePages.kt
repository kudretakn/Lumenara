package dev.lumenara.tv

import androidx.compose.runtime.*
import dev.lumenara.data.Item
import dev.lumenara.data.LiveCursor
import dev.lumenara.data.LiveWindowCursor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

internal const val LIVE_GRID_COLUMNS = 4
internal const val LIVE_PAGE_SIZE = LIVE_GRID_COLUMNS * 14

/** Absolute lazy-list indices keep focus/scroll stable while old pages leave memory. */
@Stable
internal class LivePages(val source: String, val category: String, val query: String, val shelf: String) {
    var count by mutableIntStateOf(0)
    var countLoaded by mutableStateOf(false)
    var anchor by mutableStateOf<Int?>(null)
    var center by mutableIntStateOf(0)
    val pages = mutableStateMapOf<Int, List<Item>>()
    // One skinny boundary per visited page, rather than the preceding pages' Item objects.
    val boundaries = mutableMapOf<Int, LiveWindowCursor>()
    operator fun get(index: Int): Item? = pages[index / LIVE_PAGE_SIZE]?.getOrNull(index % LIVE_PAGE_SIZE)
    fun focused(index: Int) { center = index / LIVE_PAGE_SIZE }
    fun loaded(): List<Item> = LiveChannelGroup(pages.toSortedMap().values.flatten(), source, category, query, shelf)
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
    val state = remember(source, category, query, shelf) { LivePages(source, category, query, shelf) }
    val count by remember(source, category, query, shelf) {
        model.repo.liveCount(source, category, query, shelf)
    }.collectAsLoadingState()
    SideEffect { state.count = count ?: 0; state.countLoaded = count != null }
    LaunchedEffect(state, returnId) {
        val index = returnId?.let { model.repo.liveIndex(source, it, category, query, shelf) } ?: 0
        state.center = index / LIVE_PAGE_SIZE
        state.anchor = index
    }
    val total = count
    if (state.anchor != null && total != null) {
        val lastPage = (total - 1).coerceAtLeast(0) / LIVE_PAGE_SIZE
        val center = state.center.coerceIn(0, lastPage)
        val firstPage = (center - 1).coerceAtLeast(0)
        val endPage = (center + 1).coerceAtMost(lastPage)
        LaunchedEffect(state, firstPage, endPage) {
            val offset = firstPage * LIVE_PAGE_SIZE
            val after = state.boundaries[firstPage]
            var initial = true
            // One observed window replaces three independently subscribed pages. Initial open
            // is COUNT + one window; adjacent windows seek with the preceding boundary.
            model.repo.liveWindow(source, category, query, shelf,
                (endPage - firstPage + 1) * LIVE_PAGE_SIZE, offset, after).collect { window ->
                val rows = window.rows
                if (!initial) state.boundaries.clear()
                state.boundaries.entries.removeAll { (_, boundary) ->
                    boundary.revision != window.revision || boundary.hidden != window.hidden
                }
                initial = false
                state.pages.keys.toList().filter { it !in firstPage..endPage }.forEach { state.pages.remove(it) }
                for (page in firstPage..endPage) {
                    val start = (page - firstPage) * LIVE_PAGE_SIZE
                    val chunk = rows.drop(start).take(LIVE_PAGE_SIZE)
                    state.pages[page] = chunk
                    if (chunk.size == LIVE_PAGE_SIZE) chunk.last().let {
                        state.boundaries[page + 1] = LiveWindowCursor(LiveCursor(it.title, it.id), window.revision, window.hidden)
                    }
                }
            }
        }
    }
    return state
}
