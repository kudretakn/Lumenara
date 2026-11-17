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
    var limit by rememberSaveable(source, category, query) { mutableIntStateOf(LIVE_PAGE_SIZE) }
    var focusedId by rememberSaveable(source) { mutableStateOf<String?>(null) }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { wallClockTicks(30_000).collect { now = it } }
    val groups by remember(source) { model.repo.liveCategoryCounts(source) }
        .collectAsLoadingState()
    val recent by remember(source) { model.repo.recentLive(source) }
        .collectAsLoadingState()
    val favorites by remember(source) { model.repo.shelfCount(source, "LIVE", "favorite") }
        .collectAsLoadingState()
    var airingCount by remember(source) { mutableStateOf<Int?>(null) }
    val total = groups.orEmpty().sumOf { it.count }
    val entries = if (groups == null || recent == null || favorites == null) null else listOf(
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
            val index = model.repo.liveIndex(source, target)
            if (index == null) onChannelFocusRestored()
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
            val paged = if (category !in listOf("airing", "recent"))
                rememberLivePages(model, source, provider, searchable(query),
                    if (category == "favorite") "favorite" else "", restoreId) else null
            val rows by remember(source, category, query, limit) {
                if (category == "airing") model.repo.nowAiring(source, snapshotFlow { now }, searchable(query))
                    .onEach { if (query.isBlank()) airingCount = it.size }.flatMapLatest { hits ->
                    val ids = hits.take(limit).map { it.itemId }
                    model.repo.liveByIds(source, ids).map { items ->
                        val byId = items.associateBy { it.id }
                        ids.mapNotNull { byId[it] }
                    }
                } else if (category == "recent") model.repo.recentLive(source).map { rows ->
                    rows.filter { it.search.contains(searchable(query)) }.take(limit)
                } else flowOf(emptyList())
            }.collectAsLoadingState()
            val channelNumbers = liveChannelNumbers(model, source, paged?.loaded() ?: rows.orEmpty())
            pages.SaveableStateProvider("channels-$category") {
                LiveChannelGrid(model, categoryTitle, rows, channelNumbers, now, query, { query = it },
                    limit, { limit += LIVE_PAGE_SIZE }, returnToChannel ?: focusedId, { item ->
                        focusedId = item.id
                        numberSource = item.source
                    }, onChannelFocusRestored, open, play, paged, entries.orEmpty().filter { it.id != "timeline" }) {
                        category = it.id
                        categoryTitle = it.name
                        query = ""
                    }
            }
        }
        NumberEntry(numbers)
    }
}

@Composable
private fun LiveChannelGrid(model: LibraryModel, title: String, result: List<Item>?,
    numbers: Map<String, Int>, now: Long, query: String, search: (String) -> Unit,
    limit: Int, more: () -> Unit, returnId: String?, focused: (Item) -> Unit,
    restored: () -> Unit, open: (Item) -> Unit, play: (Item, List<Item>) -> Unit, paged: LivePages? = null,
    filters: List<CategoryEntry> = emptyList(), filter: (CategoryEntry) -> Unit = {}) {
    val rows = result.orEmpty()
    val loading = if (paged != null) !paged.countLoaded else result == null
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    PrefetchArtwork(model, grid, rows, columns = LIVE_GRID_COLUMNS, paged = paged)
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
        if (!positioned && !loading && count == 0 && !searching) searchFocus.requestFocus()
        if (!positioned && targetReady && target < count) {
            grid.scrollToItem(target)
            positioned = true
        }
    }
    LaunchedEffect(paged, positioned, requestPending) {
        // Keep the return page pinned until its card has received focus.
        if (paged != null && positioned && !requestPending)
            snapshotFlow { grid.firstVisibleItemIndex }.collect { paged.focused(it) }
    }
    LaunchedEffect(targetReady) {
        if (targetReady) { withFrameNanos { }; withFrameNanos { }; Perf.since("category", "category_grid_first_draw") }
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
                        Text("$count kanal", style = LumenType.meta)
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
                modifier = Modifier.weight(1f).testTag("live-channels").focusRestorer().focusGroup(),
                contentPadding = PaddingValues(vertical = Spacing.unit),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loading) loadingCards(60.dp, LIVE_GRID_COLUMNS * 6)
                if (!loading && count == 0) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text("Eşleşen içerik yok.", color = LumenColors.textSecondary)
                }
                items(count, key = { it }) { index ->
                    val item = if (paged != null) paged[index] else rows[index]
                    if (item == null) {
                        LoadingCard(Modifier.fillMaxWidth().height(60.dp))
                        return@items
                    }
                    if (index == target && positioned && requestPending && !searching) LaunchedEffect(Unit) {
                        withFrameNanos { }
                        focus.requestFocus()
                        requestPending = false
                        restored()
                    }
                    ChannelCard(model, item, now, { Perf.mark("channel"); play(item, paged?.loaded() ?: rows) }, {
                        paged?.focused(index); focused(it)
                    },
                        Modifier.fillMaxWidth()
                            .then(if (index == target) Modifier.focusRequester(focus) else Modifier),
                        number = numbers[item.id], operations = { open(item) }, compact = true,
                        categoryName = title)
                }
                if (paged == null && count >= limit) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    FocusCard(more, Modifier.fillMaxWidth()) {
                        Text("Devamını göster", Modifier.padding(12.dp))
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
                        ListRow(onClick = { filtering = false; filter(entry) },
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
    model: LibraryModel, item: Item, number: Int, now: Long,
    play: () -> Unit, operations: () -> Unit, focus: () -> Unit,
    modifier: Modifier = Modifier, selected: Boolean = false,
    compactHeight: Dp? = null,
) {
    // LazyColumn bounds subscriptions to composed rows (including its small prefetch window).
    val slots = liveProgrammes(model, item, now, 86_400_000L)
    val current = slots.firstOrNull { now >= it.start && now < it.end }
    val logo by produceState<String?>(null, item.id, item.logo) { value = model.repo.poster(item) }
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    ListRow(play, modifier.fillMaxWidth().height(compactHeight ?: 64.dp)
        .testTag("live-channel-${item.id}").semantics { contentDescription = item.title }
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
        .onFocusChanged { if (it.isFocused) focus() }, selected = selected, interactionSource = interactions) {
        Text(number.toString(), Modifier.width(32.dp), style = LumenType.meta)
        ChannelLogo(model, item, logo, Modifier.size(40.dp, 22.5.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = LumenType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
