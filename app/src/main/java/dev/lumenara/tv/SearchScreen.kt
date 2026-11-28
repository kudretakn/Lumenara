package dev.lumenara.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.lumenara.data.debouncedSearchQuery
import dev.lumenara.data.Item
import dev.lumenara.data.ProgramHit
import dev.lumenara.design.FocusCard
import dev.lumenara.design.LumenButton
import dev.lumenara.design.LumenButtonStyle
import dev.lumenara.design.LumenColors
import dev.lumenara.design.LumenIcon
import dev.lumenara.design.LumenType
import dev.lumenara.design.Spacing
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun SearchScreen(
    model: LibraryModel,
    source: String,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
) {
    var lastShelf by rememberSaveable(source) { mutableStateOf<String?>(null) }
    var recentSearches by rememberSaveable(source) { mutableStateOf(listOf<String>()) }
    var returnTarget by rememberSaveable(source, stateSaver = Wp08SearchReturn.Saver) {
        mutableStateOf<Wp08SearchReturn?>(null)
    }
    var restoring by remember { mutableStateOf(true) }
    // Returning focus must keep the saved viewport, including queued TV pivot work
    // after requestFocus(). Release on the next interaction, not after a guessed delay.
    val keepReturnViewport = remember { mutableStateOf(lastShelf != null) }
    val platformScroll = LocalBringIntoViewSpec.current
    val returnScroll = remember(platformScroll) {
        object : BringIntoViewSpec by platformScroll {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (keepReturnViewport.value) 0f
                else platformScroll.calculateScrollDistance(offset, size, containerSize)
        }
    }
    var restoreShelf by remember { mutableStateOf<String?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    val entryFocus = remember { FocusRequester() }
    // The wide input's spatial centre can point at a later card. Keep DOWN on the
    // first absolute slot, including while the combined row separates into shelves.
    val firstResultFocus = remember { FocusRequester() }
    val resultsState = rememberLazyListState()
    val catalogStates = mapOf(
        "Kanallar" to rememberLazyListState(),
        "Filmler" to rememberLazyListState(),
        "Diziler" to rememberLazyListState(),
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (lastShelf == null) {
            withFrameNanos { }
            entryFocus.requestFocus()
            restoring = false
        }
    }
    // Read raw text in the field's composition scope, not in the shelves' scope.
    val query = rememberSaveable { mutableStateOf("") }
    val queryBlank by remember { derivedStateOf { query.value.isBlank() } }
    val queryTooShort by remember {
        derivedStateOf { dev.lumenara.catalog.searchable(query.value).length < 2 }
    }
    LaunchedEffect(queryTooShort) {
        if (queryTooShort) {
            // Clearing a query also resets shelves that are currently offscreen.
            (listOf(resultsState) + catalogStates.values).forEach { state ->
                if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) state.scrollToItem(0)
            }
            if (restoring && lastShelf != null) {
                withFrameNanos { }
                entryFocus.requestFocus()
                restoring = false
                restoreShelf = null
            }
        }
    }
    val settledQuery by remember(source) {
        snapshotFlow { query.value }.flowOn(Dispatchers.Main.immediate)
            .debouncedSearchQuery().flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle("", context = Dispatchers.Main.immediate)
    val searchPrefill by model.searchPrefill.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LaunchedEffect(searchPrefill) {
        searchPrefill?.let { query.value = it.second; model.searchPrefill.value = null }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.let { spoken -> query.value = spoken }
        }
    }
    fun listen() {
        runCatching {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR"))
        }.onFailure { model.notice.value = "Ses tanıma hizmeti bulunamadı. Metinle arayabilirsiniz." }
    }
    val request by model.remoteSearch.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LaunchedEffect(request) {
        if (request > 0) {
            model.remoteSearch.value = 0
            listen()
        }
    }
    val hidden by remember(model.repo) { model.repo.hidden }.collectAsState(initial = null, context = Dispatchers.Main.immediate)
    val pendingShelves = key(source, hidden) {
        if (settledQuery.isEmpty()) emptyList() else
            listOf("Kanallar" to "LIVE", "Filmler" to "MOVIE", "Diziler" to "SERIES").map { (title, kind) ->
                title to rememberCatalogPages(model, source, kind, query = settledQuery, pageSize = 20, columns = 1,
                    returnId = returnTarget?.takeIf {
                        restoring && it.shelf == title && it.matches(source, settledQuery, hidden.orEmpty())
                    }?.itemId)
            }
    }
    val shelves = key(source, hidden) {
        rememberSearchResults(settledQuery,
            pendingShelves.takeIf { rows -> rows.all { it.second.firstPageLoaded } }, emptyList())
    }
    // Pager instances also change on return/visibility updates. Only a completed
    // replacement query resets the viewports, including offscreen shelves.
    var displayedQuery by remember {
        mutableStateOf(returnTarget?.let { it.source to it.query })
    }
    val resultsQuery = shelves.firstOrNull()?.second?.let { it.source to it.query }
    LaunchedEffect(resultsQuery) {
        if (resultsQuery != null) {
            if (displayedQuery != null && displayedQuery != resultsQuery) {
                (listOf(resultsState) + catalogStates.values).forEach { state ->
                    if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) state.scrollToItem(0)
                }
            }
            displayedQuery = resultsQuery
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { wallClockTicks(30_000).collect { now = it } }
    val programFlow = remember(source, settledQuery, hidden) {
        if (settledQuery.isEmpty()) flowOf(emptyList())
        else model.repo.searchPrograms(source, settledQuery,
            snapshotFlow { (now - 7 * 86_400_000L) to now }.flowOn(Dispatchers.Main.immediate))
            .flatMapLatest { hits ->
                val ids = hits.map { it.itemId }.distinct()
                if (ids.isEmpty()) flowOf(emptyList())
                else model.repo.liveByIds(source, ids).map { items ->
                    val byId = items.associateBy { it.id }
                    hits.mapNotNull { hit -> byId[hit.itemId]?.let { hit to it } }
                }
            }.flowOn(Dispatchers.Default)
    }
    val programResult by key(programFlow) {
        programFlow.collectAsStateWithLifecycle(initialValue = null, context = Dispatchers.Main.immediate)
    }
    val loading = pendingShelves.any { !it.second.firstPageLoaded } || programResult == null
    val programs = key(source, hidden) {
        rememberSearchResults(settledQuery, programResult, emptyList())
    }
    val firstCatalogShelf = shelves.firstOrNull { it.second.count > 0 }?.first
    val firstResultsState = firstCatalogShelf?.let { catalogStates.getValue(it) }
    LaunchedEffect(shelves, programs, loading) {
        withContext(Dispatchers.Main.immediate) {
            if (restoring && lastShelf != null && !loading && settledQuery.isNotEmpty()) {
                val visible = shelves.filter { it.second.count > 0 }.map { it.first } +
                    if (programs.isNotEmpty()) listOf("Programlar") else emptyList()
                val saved = returnTarget?.takeIf { it.matches(source, settledQuery, hidden.orEmpty()) }
                // A removed/hidden shelf falls back to the first available shelf.
                val target = saved?.shelf?.takeIf { it in visible }
                    ?: lastShelf?.takeIf { returnTarget == null && it in visible }
                    ?: visible.firstOrNull()
                if (target == null) {
                    entryFocus.requestFocus()
                    restoring = false
                } else {
                    val index = visible.indexOf(target)
                    if (saved != null && target == saved.shelf) {
                        resultsState.scrollToItem(saved.firstShelf, saved.shelfOffset)
                    } else resultsState.scrollToItem(index)
                    if (target == "Programlar") restoring = false
                    else restoreShelf = target
                }
            }
        }
    }

    val notice by model.notice.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    CompositionLocalProvider(LocalBringIntoViewSpec provides returnScroll) {
        Column(Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) keepReturnViewport.value = false
            false
        },
            verticalArrangement = Arrangement.spacedBy(Spacing.titleGap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                SearchField(query, entryFocus, { focused ->
                    val text = query.value.trim()
                    if (fieldFocused && !focused && text.isNotEmpty()) {
                        recentSearches = (listOf(text) + recentSearches.filterNot { it.equals(text, true) }).take(6)
                    }
                    fieldFocused = focused
                    if (focused) keepReturnViewport.value = false
                }, Modifier.weight(1f), if (firstCatalogShelf != null) {
                    {
                        scope.launch(Dispatchers.Main.immediate) {
                            resultsState.scrollToItem(0)
                            firstResultsState?.scrollToItem(0)
                            withFrameNanos { }
                            firstResultFocus.requestFocus()
                        }
                    }
                } else null)
                LumenButton(onClick = { listen() }, style = LumenButtonStyle.Secondary,
                    modifier = Modifier.size(56.dp).testTag("search-voice")) {
                    LumenIcon(LumenIcon.Search, contentDescription = "Sesle ara")
                }
            }
            if (notice.isNotEmpty()) Text(notice, color = LumenColors.textSecondary, style = LumenType.meta)
            if (queryBlank && !fieldFocused && recentSearches.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    Text("SON ARAMALAR", style = LumenType.caption, color = LumenColors.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                        recentSearches.forEach { term ->
                            LumenButton(onClick = { query.value = term }, style = LumenButtonStyle.Secondary) {
                                Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).testTag("search-results").reportScrollPosition(resultsState).focusRestorer().focusGroup(), state = resultsState,
                contentPadding = PaddingValues(bottom = Spacing.unit), verticalArrangement = Arrangement.spacedBy(Spacing.shelfGap)) {
                if (queryBlank && recentSearches.isEmpty()) item(key = "hint") {
                    Text("Kanal, film veya dizi ara", style = LumenType.meta)
                }
                if (loading && shelves.none { it.second.count > 0 } && programs.isEmpty()) item(key = "loading") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(8) { LoadingCard(Modifier.width(140.dp).aspectRatio(2f / 3f)) }
                    }
                }
                if (!loading && programs.isEmpty() && shelves.all { it.second.count == 0 }) item(key = "empty") { Text("Eşleşen içerik yok.", color = LumenColors.textSecondary, style = LumenType.body) }
                val populated = shelves.filter { it.second.count > 0 }
                val displayed = if (fieldFocused && populated.isNotEmpty()) listOf(populated)
                    else populated.map { listOf(it) }
                items(displayed, key = { it.first().first }, contentType = { "catalog" }) { rowShelves ->
                    val title = rowShelves.first().first
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                        Text(if (fieldFocused) "SONUÇLAR" else title,
                            style = if (fieldFocused) LumenType.caption else LumenType.cardTitle,
                            color = if (fieldFocused) LumenColors.textSecondary else LumenColors.textPrimary)
                        // The first shelf keeps its LazyRow and focused slot when DPAD leaves the field.
                        SearchPagingRow(model, rowShelves,
                            if (!fieldFocused && title == "Kanallar") "channel-shelf-$title" else "search-shelf-$title",
                            {
                                lastShelf = it
                                if (!restoring) keepReturnViewport.value = false
                            }, open, play,
                            if (title == firstCatalogShelf) firstResultFocus else null,
                            catalogStates.getValue(title),
                            awaitingReturn = restoring,
                            restore = if (restoreShelf == title) returnTarget?.takeIf {
                                it.shelf == title && it.matches(source, settledQuery, hidden.orEmpty())
                            } else null,
                            restoreFirst = restoreShelf == title,
                            onRestored = { restoring = false; restoreShelf = null },
                            onActivate = { shelf, item, index, first, offset ->
                                returnTarget = Wp08SearchReturn(source, settledQuery, shelf, item.id, index,
                                    first, offset, resultsState.firstVisibleItemIndex,
                                    resultsState.firstVisibleItemScrollOffset, hidden.orEmpty().toList())
                            })
                    }
                }
                if (programs.isNotEmpty()) item(key = "programs") {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Programlar", style = LumenType.cardTitle)
                        LazyRow(Modifier.testTag("search-shelf-Programlar").focusRestorer().focusGroup(),
                            contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(programs, key = { (hit, _) -> "${hit.itemId}:${hit.slot.feed}:${hit.slot.channel}:${hit.slot.start}" }) { (hit, channel) ->
                                ProgramCard(hit, channel, now, { lastShelf = "Programlar" }) {
                                    returnTarget = null
                                    val slot = hit.slot
                                    val atClick = System.currentTimeMillis()
                                    when {
                                        atClick in slot.start until slot.end -> play(channel, programs.map { it.second }.distinctBy { it.id })
                                        channel.archive.isNotEmpty() && slot.end <= atClick &&
                                            slot.start >= atClick - channel.archiveDays.coerceIn(0, 365) * 86_400_000L ->
                                            model.job("Arşiv programı açıldı.") {
                                                val replay = model.repo.replay(channel, slot)
                                                play(replay, listOf(replay))
                                            }
                                        else -> open(channel)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Null means a replacement is still loading; empty results are a completed search. */
@Composable
internal fun <T> rememberSearchResults(query: String, incoming: T?, empty: T): T {
    var previous by remember { mutableStateOf(empty) }
    val displayed = if (query.isEmpty()) empty else incoming ?: previous
    SideEffect { previous = displayed }
    return displayed
}

@Composable
private fun SearchField(
    query: MutableState<String>,
    requester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onDown: (() -> Unit)? = null,
) {
    val value by query
    Row(modifier.height(56.dp).background(LumenColors.surface3, RoundedCornerShape(8.dp))
        .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        LumenIcon(LumenIcon.Search, contentDescription = null, tint = LumenColors.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = { query.value = it },
            modifier = Modifier.weight(1f).fillMaxHeight().wrapContentHeight(Alignment.CenterVertically)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionDown && onDown != null) {
                        if (event.type == KeyEventType.KeyDown) onDown()
                        true
                    } else false
                }
                .focusRequester(requester).onFocusChanged {
                    onFocus(it.isFocused)
                }.testTag("search-input"),
            textStyle = TextStyle(color = LumenColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(LumenColors.accent),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Kanal, film veya dizi ara", style = LumenType.body,
                        color = LumenColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

@Composable
internal fun SearchPagingRow(
    model: LibraryModel,
    shelves: List<Pair<String, CatalogPages>>,
    tag: String,
    focus: (String) -> Unit,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
    firstResult: FocusRequester? = null,
    state: LazyListState = rememberLazyListState(),
    awaitingReturn: Boolean = false,
    restore: Wp08SearchReturn? = null,
    restoreFirst: Boolean = false,
    onRestored: () -> Unit = {},
    onActivate: (String, Item, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
) {
    val returnFocus = remember { FocusRequester() }
    val restorePages = shelves.firstOrNull()?.second
    var missingReturnId by remember(restorePages, restore) { mutableStateOf(false) }
    val returnIndex = if (restore != null && !missingReturnId) restorePages?.anchor ?: 0 else 0
    LaunchedEffect(restoreFirst, restorePages, returnIndex) {
        withContext(Dispatchers.Main.immediate) {
            if (restoreFirst && restorePages != null) {
                // A distant first page initially exposes only its end as the provisional
                // count. Wait for the total so LazyRow cannot clamp the saved offset.
                snapshotFlow { restorePages.countLoaded && restorePages[returnIndex] != null }.first { it }
                val sameItem = restorePages[returnIndex]?.id == restore?.itemId
                if (restore != null && !sameItem && returnIndex != 0) {
                    // Refresh may remove the ID between the index probe and page read.
                    missingReturnId = true
                    restorePages.focused(0)
                    return@withContext
                }
                val (first, offset) = restore?.viewport(returnIndex, sameItem) ?: (0 to 0)
                if (state.firstVisibleItemIndex != first || state.firstVisibleItemScrollOffset != offset) {
                    state.scrollToItem(first, offset)
                }
                // Layout must attach the target slot before the one return focus request.
                snapshotFlow { state.layoutInfo.visibleItemsInfo.any { it.index == returnIndex } }.first { it }
                withFrameNanos { }
                returnFocus.requestFocus()
                onRestored()
            }
        }
    }

    val counts = shelves.map { it.second.count }
    fun slot(index: Int): Pair<Int, Int> {
        var local = index
        shelves.indices.forEach { shelf ->
            if (local < counts[shelf]) return shelf to local
            local -= counts[shelf]
        }
        return shelves.lastIndex to local
    }
    LaunchedEffect(state, shelves, counts, awaitingReturn) {
        withContext(Dispatchers.Main.immediate) {
            if (awaitingReturn) return@withContext
            snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged().collect { indices ->
                    indices.groupBy { slot(it).first }.forEach { (shelf, visible) ->
                        shelves[shelf].second.visible(slot(visible.last()).second)
                    }
                }
        }
    }

    LazyRow(Modifier.testTag(tag).reportScrollPosition(state).semantics {
        this[Wp08SearchResultCountKey] = if (shelves.all { it.second.countLoaded }) counts.sum() else -1
        if (shelves.size == 1) pagingWindow = pagingDescription(shelves.single().second)
    }.focusRestorer().focusGroup(), state = state,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(counts.sum(), key = { index ->
            val (shelf, local) = slot(index)
            "${shelves[shelf].first}:$local"
        }, contentType = { "search-result" }) { index ->
            val (shelf, local) = slot(index)
            val (title, pages) = shelves[shelf]
            val entry = pages[local]
            val wrapper = when {
                tag.startsWith("channel-shelf-") -> Modifier
                title == "Kanallar" -> Modifier.testTag("channel-shelf-Kanallar")
                shelves.size > 1 && shelf > 0 -> Modifier.testTag("search-shelf-$title")
                else -> Modifier
            }
            Box(wrapper) {
                SearchResultCard(model, entry, "$title:$local", when {
                    restoreFirst && index == returnIndex -> returnFocus
                    index == 0 -> firstResult
                    else -> null
                }, {
                    pages.focused(local)
                    focus(title)
                }) {
                    entry?.let {
                        onActivate(title, it, local, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                        if (title == "Kanallar") play(it, pages.loaded()) else open(it)
                    }
                }
            }
        }
    }
}

/** The focus surface survives both page arrival and eviction. */
@Composable
private fun SearchResultCard(model: LibraryModel, item: Item?, slot: String,
    entryFocus: FocusRequester?, focus: () -> Unit, click: () -> Unit) {
    val ownRequester = remember { FocusRequester() }
    val requester = entryFocus ?: ownRequester
    Column(Modifier.width(140.dp)) {
        // The resting slot is measured separately from the 1.06 focus transform.
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)
            .then(if (item == null) Modifier else Modifier.testTag("search-artwork-${item.id}"))) {
            FocusCard({ if (item != null) click() }, Modifier.fillMaxSize().focusRequester(requester)
                .testTag("search-slot-$slot")
                .semantics { contentDescription = item?.title ?: "Yükleniyor" }
                .onFocusChanged { if (it.isFocused) focus() }) {
                // Keep the focus slot, but never show the previous item's artwork in it.
                key(item?.id) {
                    if (item == null) Box(Modifier.fillMaxSize().background(LumenColors.surface2))
                    else if (item.kind == "LIVE") {
                        val logo by produceState<String?>(null, item.id, item.logo) { value = model.repo.poster(item) }
                        ChannelLogo(model, item, logo, Modifier.fillMaxSize())
                    } else Poster(model, item, Modifier.fillMaxSize())
                }
            }
        }
        Text(item?.title ?: " ", Modifier.padding(top = 8.dp), style = LumenType.label,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item?.let { it.year.ifBlank { it.category } } ?: " ", style = LumenType.meta,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProgramCard(hit: ProgramHit, channel: Item, now: Long, focus: () -> Unit, click: () -> Unit) {
    var restoreFocus by rememberSaveable(channel.id, hit.slot.start) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (restoreFocus) { withFrameNanos { }; requester.requestFocus(); restoreFocus = false }
    }
    val slot = hit.slot
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(slot.start).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val label = when (day) {
        today -> "Bugün"
        today.plusDays(1) -> "Yarın"
        today.minusDays(1) -> "Dün"
        else -> day.format(DateTimeFormatter.ofPattern("dd.MM"))
    }
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    FocusCard({ restoreFocus = true; click() }, Modifier.width(140.dp).aspectRatio(2f / 3f).focusRequester(requester)
        .onFocusChanged { if (it.isFocused) focus() }.testTag("search-program-${channel.id}-${slot.start}")) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(slot.title, style = LumenType.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(channel.title, style = LumenType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$label · ${time.format(Instant.ofEpochMilli(slot.start))}–${time.format(Instant.ofEpochMilli(slot.end))}",
                style = LumenType.meta, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (now in slot.start until slot.end) Text("Şimdi", color = LumenColors.accent, style = LumenType.caption)
        }
    }
}
