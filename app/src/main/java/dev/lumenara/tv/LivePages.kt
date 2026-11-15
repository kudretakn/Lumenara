package dev.lumenara.tv

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lumenara.data.Item
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

internal const val LIVE_PAGE_SIZE = 80

/** Absolute lazy-list indices keep focus/scroll stable while old pages leave memory. */
@Stable
internal class LivePages(val source: String, val category: String, val query: String, val shelf: String) {
    var count by mutableIntStateOf(0)
    var anchor by mutableStateOf<Int?>(null)
    var center by mutableIntStateOf(0)
    val pages = mutableStateMapOf<Int, List<Item>>()
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
    val index = model.repo.liveIndex(group.source, item.id, group.category, group.query, group.shelf) ?: return null
    val next = index + delta
    if (next < 0) return null
    val offset = next / LIVE_PAGE_SIZE * LIVE_PAGE_SIZE
    val rows = model.repo.livePage(group.source, group.category, group.query, group.shelf, LIVE_PAGE_SIZE, offset).first()
    val target = rows.getOrNull(next - offset) ?: return null
    return target to LiveChannelGroup(rows, group.source, group.category, group.query, group.shelf)
}

@Composable
internal fun rememberLivePages(model: LibraryModel, source: String, category: String = "",
    query: String = "", shelf: String = "", returnId: String? = null): LivePages {
    val state = remember(source, category, query, shelf) { LivePages(source, category, query, shelf) }
    val count by remember(source, category, query, shelf) {
        model.repo.liveCount(source, category, query, shelf)
    }.collectAsStateWithLifecycle(null)
    SideEffect { state.count = count ?: 0 }
    LaunchedEffect(state, returnId) {
        val index = returnId?.let { model.repo.liveIndex(source, it, category, query, shelf) } ?: 0
        state.center = index / LIVE_PAGE_SIZE
        state.anchor = index
    }
    val total = count
    if (state.anchor != null && total != null) {
        val lastPage = (total - 1).coerceAtLeast(0) / LIVE_PAGE_SIZE
        val center = state.center.coerceIn(0, lastPage)
        val wanted = ((center - 1).coerceAtLeast(0)..(center + 1).coerceAtMost(lastPage)).toSet()
        LaunchedEffect(state, wanted) { state.pages.keys.toList().filter { it !in wanted }.forEach { state.pages.remove(it) } }
        for (page in wanted) key(state, page) {
            LaunchedEffect(state, page) {
                model.repo.livePage(source, category, query, shelf, LIVE_PAGE_SIZE, page * LIVE_PAGE_SIZE)
                    .collect { state.pages[page] = it }
            }
        }
    }
    return state
}
