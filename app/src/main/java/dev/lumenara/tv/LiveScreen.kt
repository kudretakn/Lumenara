package dev.lumenara.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lumenara.catalog.searchable
import dev.lumenara.data.Item
import dev.lumenara.design.*
import kotlinx.coroutines.flow.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
internal fun LiveScreen(
    model: LibraryModel,
    source: String,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
    returnToChannel: String? = null,
    onChannelFocusRestored: () -> Unit = {},
) {
    var numberSource by remember(source) { mutableStateOf(source) }
    val numbers = rememberLiveNumberControl(model, numberSource, select = play)
    var category by rememberSaveable(source) { mutableStateOf<String?>(null) }
    var categoryTitle by rememberSaveable(source) { mutableStateOf("") }
    var query by rememberSaveable(source, category) { mutableStateOf("") }
    var focusedId by rememberSaveable(source) { mutableStateOf<String?>(null) }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { wallClockTicks(30_000).collect { now = it } }
    val groups by remember(source) { model.repo.liveCategoryCounts(source) }
        .collectAsLoadingState()
    var categoryExtrasReady by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source, groups != null) {
        if (groups != null) {
            withFrameNanos { }; withFrameNanos { }
            categoryExtrasReady = true
        }
    }
    val recent by remember(source, categoryExtrasReady) {
        if (categoryExtrasReady) model.repo.recentLive(source) else emptyFlow()
    }
        .collectAsLoadingState()
    val favorites by remember(source, categoryExtrasReady) {
        if (categoryExtrasReady) model.repo.liveCount(source, shelf = "favorite") else emptyFlow()
    }
        .collectAsLoadingState()
    var airingCount by remember(source) { mutableStateOf<Int?>(null) }
    val total = groups.orEmpty().sumOf { it.count }
    val entries = if (groups == null) null else listOf(
        CategoryEntry("all", "Tüm kanallar", total),
        CategoryEntry("recent", "Son izlenenler", recent.orEmpty().size),
        CategoryEntry("favorite", "Favoriler", favorites ?: 0),
        CategoryEntry("airing", "Şu an yayında", airingCount ?: total, countKnown = airingCount != null),
        CategoryEntry("timeline", "Yayın akışı", total),
    ).filter { it.count > 0 || (it.id == "airing" && total > 0) } + providerCategories(groups.orEmpty())
    androidx.activity.compose.BackHandler(category != null) { category = null }
    LaunchedEffect(source, returnToChannel) {
        val target = returnToChannel ?: return@LaunchedEffect
        if (target != focusedId) {
            category = "all"
            categoryTitle = "Tüm kanallar"
            query = ""
            // rememberLivePages resolves the return index once and falls back to the first card.
        }
    }
    if (category == "timeline") {
        pages.SaveableStateProvider("timeline") {
            Timeline(model, source, open, play) { category = null }
        }
        return
    }
    Box(Modifier.fillMaxSize().testTag("live-screen")
        .onPreviewKeyEvent { numbers.key(it.nativeKeyEvent) }) {
        if (category == null) {
            pages.SaveableStateProvider("categories") {
                CategoryGrid("Canlı TV", entries) { category = it.id; categoryTitle = it.name }
            }
        } else {
            val provider = category?.removePrefix("provider:")
                .takeIf { category?.startsWith("provider:") == true }.orEmpty()
            val restoreId = remember(source, category, query) { returnToChannel ?: focusedId }
            val airingIds by remember(source, category, query) {
                if (category == "airing") model.repo.nowAiring(source, snapshotFlow { now }, searchable(query))
                    .onEach { if (query.isBlank()) airingCount = it.size }.map { hits -> hits.map { it.itemId }.distinct() }
                else flowOf(emptyList())
            }.collectAsLoadingState()
            val paged = when (category) {
                "airing" -> rememberAiringPages(model, source, query, airingIds, restoreId)
                "recent" -> null
                else -> rememberLivePages(model, source, provider, searchable(query),
                    if (category == "favorite") "favorite" else "", restoreId)
            }
            val rows by remember(source, category, query) {
                if (category == "recent") model.repo.recentLive(source).map { rows ->
                    rows.filter { it.search.contains(searchable(query)) }
                } else flowOf(emptyList())
            }.collectAsLoadingState()
            pages.SaveableStateProvider("channels-$category") {
                LiveChannelGrid(model, categoryTitle, rows, source, now, query, { focusedId = null; query = it },
                    returnToChannel ?: focusedId, { item ->
                        focusedId = item.id
                        numberSource = item.source
                    }, onChannelFocusRestored, open, play, paged, entries.orEmpty().filter { it.id != "timeline" }) {
                        category = it.id
                        categoryTitle = it.name
                        query = ""
                        focusedId = null
                    }
            }
        }
        NumberEntry(numbers)
    }
}

/** Programme discovery keeps only skinny channel IDs; Item/logo rows use the shared window. */
@Composable
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun rememberAiringPages(model: LibraryModel, source: String, query: String,
    ids: List<String>?, returnId: String?): WindowPages {
    val hidden by remember(model.repo) { model.repo.hidden }.collectAsState(initial = null)
    val state = remember(source, query, hidden) { WindowPages(LIVE_PAGE_SIZE, LIVE_GRID_COLUMNS) }
    val currentIds by rememberUpdatedState(ids.orEmpty())
    val restoreOnce = remember { booleanArrayOf(true) }
    val restoreId = remember(state) {
        returnId.takeIf { hidden != null && restoreOnce[0] }.also { if (hidden != null) restoreOnce[0] = false }
    }
    if (hidden != null && ids != null) rememberWindowPagesDriver(state,
        page = { first, last -> snapshotFlow { currentIds }.flatMapLatest { all ->
            val selected = all.drop(first * LIVE_PAGE_SIZE).take((last - first + 1) * LIVE_PAGE_SIZE)
            if (selected.isEmpty()) flowOf(emptyList()) else model.repo.liveByIds(source, selected).map { rows ->
                val byId = rows.associateBy { it.id }
                selected.mapNotNull { byId[it] }
            }
        } },
        total = { snapshotFlow { currentIds.size } },
        index = { restoreId?.let { currentIds.indexOf(it).coerceAtLeast(0) } ?: 0 })
    return state
}

@Composable
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun LiveChannelGrid(model: LibraryModel, title: String, result: List<Item>?,
    source: String, now: Long, query: String, search: (String) -> Unit,
    returnId: String?, focused: (Item) -> Unit,
    restored: () -> Unit, open: (Item) -> Unit, play: (Item, List<Item>) -> Unit, paged: WindowPages? = null,
    filters: List<CategoryEntry> = emptyList(), filter: (CategoryEntry) -> Unit = {}) {
    val rows = result.orEmpty()
    val loading = if (paged != null) !paged.firstPageLoaded else result == null
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val gridFocus = rememberGridFocus(grid) { it % LIVE_GRID_COLUMNS }
    var decorationsReady by remember(paged, query) { mutableStateOf(false) }
    LaunchedEffect(paged, query, loading) {
        if (!loading) {
            withFrameNanos { }; withFrameNanos { }
            decorationsReady = true
        }
    }
    // Badges, logo prefetch and EPG must not start whole-catalog work before the cards draw.
    val numbers = if (paged is LivePages && source.isNotEmpty() && paged.category.isEmpty() &&
        paged.query.isEmpty() && paged.shelf.isEmpty()) {
        paged.pages.flatMap { (page, items) ->
            items.mapIndexed { index, item -> item.id to page * LIVE_PAGE_SIZE + index + 1 }
        }.toMap()
    } else if (decorationsReady) liveChannelNumbers(model, source, paged?.loaded() ?: rows) else emptyMap()
    val current by remember(model.repo, grid, paged, rows, now, decorationsReady) {
        if (!decorationsReady) flowOf(emptyMap<String, dev.lumenara.data.Slot>()) else snapshotFlow {
            grid.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                if (paged != null) paged[visible.index] else rows.getOrNull(visible.index)
            }.distinctBy { it.id }
        }.flatMapLatest { visible -> model.repo.liveNow(visible, now) }
    }.collectAsStateWithLifecycle(emptyMap())
    if (decorationsReady) PrefetchArtwork(model, grid, rows, columns = LIVE_GRID_COLUMNS, paged = paged)
    val focus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var filtering by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(searching || filtering) {
        when {
            filtering -> { filtering = false; filterFocus.requestFocus() }
            else -> { searching = false; search(""); searchFocus.requestFocus() }
        }
    }
    LaunchedEffect(searching) { if (searching) { withFrameNanos { }; fieldFocus.requestFocus() } }
    var positioned by remember(paged, query) { mutableStateOf(false) }
    var requestPending by remember(paged, query) { mutableStateOf(true) }
    val restoreId = remember { returnId }
    // SQL pages bound memory; the lazy grid still needs every absolute index.
    // A limit captured before changing category belongs to the old saveable state.
    val count = paged?.count ?: rows.size
    val target = paged?.anchor ?: rows.indexOfFirst { it.id == restoreId }.coerceAtLeast(0)
    val targetReady = if (paged != null) paged.anchor != null && paged[target] != null else rows.isNotEmpty()
    LaunchedEffect(paged, target, targetReady, count, query, loading) {
        if (!positioned && !loading && count == 0 && !searching) {
            searchFocus.requestFocus()
            if (requestPending) { requestPending = false; restored() }
        }
        if (!positioned && targetReady && target < count) {
            grid.scrollToItem(target)
            positioned = true
        }
    }
    LaunchedEffect(paged, positioned, requestPending) {
        // Keep the return page pinned until its card has received focus.
        if (paged != null && positioned && !requestPending)
            snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .filterNotNull().collect { paged.visible(it) }
    }
    Box(Modifier.fillMaxSize().background(LumenColors.bg)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
                if (searching) {
                    LiveSearchField(query, search, Modifier.weight(1f).focusRequester(fieldFocus))
                } else {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                        Text(title, Modifier.weight(1f, fill = false), style = LumenType.headline,
                            color = LumenColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (paged == null || paged.countLoaded) "$count kanal" else "Kanallar", style = LumenType.meta)
                    }
                }
                LumenButton({ searching = !searching }, Modifier.focusRequester(searchFocus),
                    style = LumenButtonStyle.Secondary) {
                    LumenIcon(LumenIcon.Search, contentDescription = "Kanal ara")
                }
                LumenButton({ filtering = !filtering }, Modifier.focusRequester(filterFocus),
                    style = LumenButtonStyle.Secondary, enabled = filters.isNotEmpty()) {
                    LumenIcon(LumenIcon.Filter, contentDescription = "Filtrele")
                }
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Fixed(LIVE_GRID_COLUMNS), state = grid,
                modifier = Modifier.weight(1f).testTag("live-channels").reportScrollPosition(grid).then(gridFocus.modifier)
                    .semantics { if (paged != null) pagingWindow = pagingDescription(paged) }.focusRestorer().focusGroup()
                    .categoryFirstDraw(!loading && (count == 0 || positioned && targetReady)),
                contentPadding = PaddingValues(vertical = Spacing.unit),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loading) loadingCards(60.dp, LIVE_GRID_COLUMNS * 6)
                if (!loading && count == 0) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text("Eşleşen içerik yok.", color = LumenColors.textSecondary)
                }
                items(count, key = { it }) { index ->
                    val item = if (paged != null) paged[index] else rows[index]
                    if (item != null && index == target && positioned && requestPending && !searching) LaunchedEffect(Unit) {
                        withFrameNanos { }
                        focus.requestFocus()
                        requestPending = false
                        restored()
                    }
                    // A slot and its card have separate semantics nodes: two test tags on
                    // the card's modifier chain would hide the channel identity.
                    Box(Modifier.fillMaxWidth().testTag("live-slot-$index")) {
                        ChannelCard(model, item, now, { item?.let { Perf.mark("channel"); play(it, paged?.loaded() ?: rows) } }, {
                            paged?.focused(index); focused(it)
                        },
                            Modifier.fillMaxWidth()
                                .then(gridFocus.item(index))
                                .onFocusChanged { if (it.isFocused) paged?.focused(index) }
                                .then(if (index == target) Modifier.focusRequester(focus) else Modifier),
                            number = numbers[item?.id], operations = { item?.let(open) }, compact = true,
                            categoryName = title, programmes = listOfNotNull(current[item?.id]))
                    }
                }
            }
        }
        if (filtering) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
            Panel(Modifier.align(Alignment.TopEnd).padding(top = 56.dp).width(320.dp)) {
                LazyColumn(Modifier.padding(Spacing.unit)) {
                    itemsIndexed(filters, key = { _, entry -> entry.id }) { index, entry ->
                        ListRow(onClick = { Perf.transitions.categoryStart(); filtering = false; filter(entry) },
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier) {
                            Text(entry.name, style = LumenType.cardTitle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveChannelRow(
    model: LibraryModel, item: Item?, number: Int, now: Long,
    play: () -> Unit, operations: () -> Unit, focus: () -> Unit,
    modifier: Modifier = Modifier, selected: Boolean = false,
    compactHeight: Dp? = null,
) {
    val row = item ?: Item("", "", "", "", "LIVE", "", "", "", 0, "")
    // LazyColumn bounds subscriptions to composed rows (including its small prefetch window).
    val slots = if (item == null) emptyList() else liveProgrammes(model, item, now, 86_400_000L)
    val current = slots.firstOrNull { now >= it.start && now < it.end }
    val logo by produceState<String?>(null, row.id, row.logo) { value = item?.let { model.repo.poster(it) } }
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    LaunchedEffect(item?.id, isFocused) { if (item != null && isFocused) focus() }
    ListRow({ if (item != null) { focus(); play() } }, modifier.fillMaxWidth().height(compactHeight ?: 64.dp)
        .testTag("live-channel-${row.id}").semantics { contentDescription = row.title }
        .onPreviewKeyEvent {
            val key = it.nativeKeyEvent
            val ok = key.keyCode in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            when {
                key.keyCode == android.view.KeyEvent.KEYCODE_MENU -> {
                    if (key.action == android.view.KeyEvent.ACTION_UP) operations()
                    true
                }
                ok && key.action == android.view.KeyEvent.ACTION_UP && key.eventTime - key.downTime >= 500 -> {
                    operations(); true
                }
                else -> false
            }
        }
        .onFocusChanged { if (it.isFocused && item != null) focus() }, selected = selected, interactionSource = interactions) {
        Text(number.toString(), Modifier.width(32.dp), style = LumenType.meta)
        ChannelLogo(model, row, logo, Modifier.size(40.dp, 22.5.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = LumenType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Spacing.iconText))
            Text(current?.title.orEmpty(), style = LumenType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isFocused && current != null) ProgressLine(
                (now - current.start).toFloat() / (current.end - current.start).coerceAtLeast(1),
                Modifier.height(2.dp))
        }
    }
}

// The shared Field belongs to setup and uses an external label; live search is a single TV control.
@Composable
private fun LiveSearchField(query: String, search: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(48.dp).background(LumenColors.surface3, RoundedCornerShape(Radii.button))
        .padding(horizontal = Spacing.cardGap), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        LumenIcon(LumenIcon.Search, contentDescription = null, tint = LumenColors.textSecondary)
        BasicTextField(query, search, Modifier.weight(1f).semantics { contentDescription = "Kanal arama alanı" },
            singleLine = true, textStyle = LumenType.body.copy(color = LumenColors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(LumenColors.textPrimary),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) Text("Kanal ara", style = LumenType.body, color = LumenColors.textSecondary)
                    field()
                }
            })
    }
}
