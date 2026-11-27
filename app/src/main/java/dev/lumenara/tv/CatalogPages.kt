package dev.lumenara.tv

import androidx.compose.runtime.*
import dev.lumenara.data.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/** Absolute slots and a bounded Item window are independent of the Lazy layout's keys. */
@Stable
internal open class WindowPages(val pageSize: Int, val columns: Int) {
    var count by mutableIntStateOf(0)
    var countLoaded by mutableStateOf(false)
    var firstPageLoaded by mutableStateOf(false)
    var anchor by mutableStateOf<Int?>(null)
    var center by mutableIntStateOf(0)
    val pages = mutableStateMapOf<Int, List<Item>>()
    private val window = PagingWindow(pageSize, pages)
    private var focusIndex: Int? = null

    operator fun get(index: Int): Item? = window[index]
    fun focused(index: Int) { focusIndex = index; center = index.coerceAtLeast(0) / pageSize }
    fun visible(index: Int) {
        // First visible and focused rows can straddle a page boundary. Prefer real focus;
        // a distant programmatic scroll has left that old focus outside the viewport.
        center = window.visibleCenter(index, focusIndex)
    }
    fun range(): IntRange = window.range(center, count, countLoaded)
    fun retain(range: IntRange) { window.retain(range) }
    fun installPage(page: Int, rows: List<Item>) {
        window.installPage(page, rows)
        if (!countLoaded) count = page * pageSize + rows.size
        firstPageLoaded = true
    }
    open fun loaded(): List<Item> = pages.toSortedMap().values.flatten()
}

@Stable
internal class CatalogPages(val source: String, val kind: String, val category: String,
    val query: String, val shelf: String, pageSize: Int, columns: Int) : WindowPages(pageSize, columns) {
    private var liveSlots by mutableStateOf(BooleanArray(0))
    private var slotColumns by mutableStateOf(IntArray(0))
    fun isLiveSlot(index: Int): Boolean = kind == "LIVE" || (liveSlots.getOrNull(index) ?: (get(index)?.kind == "LIVE"))
    fun columnOf(index: Int): Int = when (kind) {
        "" -> slotColumns.getOrNull(index) ?: 0
        "LIVE" -> index % (columns / 2) * 2
        else -> index % columns
    }
    fun slotKinds(kinds: List<String>) {
        liveSlots = BooleanArray(kinds.size) { kinds[it] == "LIVE" }
        var column = 0
        slotColumns = IntArray(kinds.size) { index ->
            val span = if (liveSlots[index]) 2 else 1
            if (column + span > columns) column = 0
            column.also { column = (column + span) % columns }
        }
    }
    override fun loaded(): List<Item> = if (kind == "LIVE")
        LiveChannelGroup(super.loaded(), source, category, query, shelf) else super.loaded()
}

/** One driver for live keyset pages, catalog OFFSET pages and derived programme windows. */
@Composable
internal fun rememberWindowPagesDriver(state: WindowPages,
    page: suspend (firstPage: Int, endPage: Int) -> Flow<List<Item>>,
    total: () -> Flow<Int>, index: suspend () -> Int) {
    val currentPage by rememberUpdatedState(page)
    val currentTotal by rememberUpdatedState(total)
    val currentIndex by rememberUpdatedState(index)
    LaunchedEffect(state) {
        state.anchor = currentIndex().coerceAtLeast(0)
        state.center = checkNotNull(state.anchor) / state.pageSize
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstPageLoaded }.first { it }
        // First indexed LIMIT has published visible cards before COUNT/adjacent work starts.
        withFrameNanos { }; withFrameNanos { }
        currentTotal().collect { state.count = it; state.countLoaded = true }
    }
    if (state.anchor != null) {
        val range = state.range()
        SideEffect { state.retain(range) }
        // Retain both rows and Room subscriptions for overlapping pages. A normal
        // one-page advance reads only 28/80 new rows, instead of the full 140/400.
        // Each retained subscription still refreshes on ordering/mark invalidation.
        for (pageIndex in range) key(state, pageIndex) {
            LaunchedEffect(state, pageIndex) {
                currentPage(pageIndex, pageIndex).collect { rows -> state.installPage(pageIndex, rows) }
            }
        }
    }
}

@Composable
internal fun rememberCatalogPages(model: LibraryModel, source: String, kind: String,
    category: String = "", query: String = "", shelf: String = "", year: String = "",
    genre: String = "", language: String = "", minRating: Double = 0.0,
    pageSize: Int = 80, columns: Int = 8, returnId: String? = null): CatalogPages {
    val hidden by remember(model.repo) { model.repo.hidden }.collectAsState(initial = null)
    val state = remember(source, kind, category, query, shelf, year, genre, language, minRating, pageSize, columns, hidden) {
        CatalogPages(source, kind, category, query, shelf, pageSize, columns)
    }
    // Consume the saved focus only on the first real state. Filters and visibility reset top.
    val restored = remember { booleanArrayOf(false) }
    val restore = remember(state) {
        if (hidden != null && !restored[0]) { restored[0] = true; returnId } else null
    }
    if (hidden != null) rememberWindowPagesDriver(state,
        page = { first, last -> model.repo.browsePage(source, kind, category, query, shelf,
            (last - first + 1) * pageSize, first * pageSize, year, genre, language, minRating)
            // Room's SQL is asynchronous; displayVersion/visibility mapping must be too.
            .let { if (query.isNotEmpty()) it.flowOn(Dispatchers.Default) else it } },
        total = {
            if (kind.isEmpty()) model.repo.browseKinds(source, kind, category, query, shelf, year, genre, language, minRating)
                .map { kinds -> state.slotKinds(kinds); kinds.size }
            else model.repo.browseCount(source, kind, category, query, shelf, year, genre, language, minRating)
        },
        index = { restore?.let { model.repo.browseIndex(source, kind, category, query, shelf, it,
            year, genre, language, minRating) } ?: 0 })
    return state
}
