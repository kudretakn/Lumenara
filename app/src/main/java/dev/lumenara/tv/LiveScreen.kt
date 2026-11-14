package dev.lumenara.tv

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.lumenara.catalog.searchable
import dev.lumenara.data.Item
import dev.lumenara.data.Slot
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
    val channelNumbers = liveChannelNumbers(model, source)
    var category by rememberSaveable(source) { mutableStateOf<String?>(null) }
    var categoryTitle by rememberSaveable(source) { mutableStateOf("") }
    var query by rememberSaveable(source, category) { mutableStateOf("") }
    var limit by rememberSaveable(source, category, query) { mutableIntStateOf(80) }
    var focusedId by rememberSaveable(source) { mutableStateOf<String?>(null) }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { wallClockTicks(30_000).collect { now = it } }
    val groups by remember(source) { model.repo.liveCategoryCounts(source) }
        .collectAsStateWithLifecycle(emptyList())
    val recent by remember(source) { model.repo.recentLive(source) }
        .collectAsStateWithLifecycle(emptyList())
    val favorites by remember(source) { model.repo.shelfCount(source, "LIVE", "favorite") }
        .collectAsStateWithLifecycle(0)
    val airing by remember(source, now) { model.repo.nowAiring(source, now) }
        .collectAsStateWithLifecycle(emptyList())
    val total = groups.sumOf { it.count }
    val entries = listOf(
        CategoryEntry("all", "Tüm kanallar", total),
        CategoryEntry("recent", "Son izlenenler", recent.size),
        CategoryEntry("favorite", "Favoriler", favorites),
        CategoryEntry("airing", "Şu an yayında", airing.size),
        CategoryEntry("timeline", "Yayın akışı", total),
    ).filter { it.count > 0 } + providerCategories(groups)
    androidx.activity.compose.BackHandler(category != null) { category = null }
    LaunchedEffect(returnToChannel) {
        val target = returnToChannel ?: return@LaunchedEffect
        if (target != focusedId) {
            category = "all"
            categoryTitle = "Tüm kanallar"
            query = ""
            val index = model.repo.browse(source, "LIVE", "", "", "", Int.MAX_VALUE)
                .first().indexOfFirst { it.id == target }
            if (index >= 0) limit = maxOf(limit, index + 1)
            else onChannelFocusRestored()
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
            val rows by remember(source, category, query, limit, if (category == "airing") now else 0L) {
                if (category == "airing") model.repo.nowAiring(source, now, searchable(query)).flatMapLatest { hits ->
                    val ids = hits.take(limit).map { it.itemId }
                    model.repo.liveByIds(source, ids).map { items ->
                        val byId = items.associateBy { it.id }
                        ids.mapNotNull { byId[it] }
                    }
                } else if (category == "recent") model.repo.recentLive(source).map { rows ->
                    rows.filter { it.search.contains(searchable(query)) }.take(limit)
                } else model.repo.browse(source, "LIVE",
                    category?.removePrefix("provider:").takeIf { category?.startsWith("provider:") == true }.orEmpty(),
                    searchable(query), if (category == "favorite") "favorite" else "", limit)
            }.collectAsStateWithLifecycle(emptyList())
            pages.SaveableStateProvider("channels-$category") {
                LiveChannelGrid(model, categoryTitle, rows, channelNumbers, now, query, { query = it },
                    limit, { limit += 80 }, returnToChannel ?: focusedId, { item ->
                        focusedId = item.id
                        numberSource = item.source
                    }, onChannelFocusRestored, open, play)
            }
        }
        NumberEntry(numbers)
    }
}

@Composable
private fun LiveChannelGrid(model: LibraryModel, title: String, rows: List<Item>,
    numbers: Map<String, Int>, now: Long, query: String, search: (String) -> Unit,
    limit: Int, more: () -> Unit, returnId: String?, focused: (Item) -> Unit,
    restored: () -> Unit, open: (Item) -> Unit, play: (Item, List<Item>) -> Unit) {
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val focus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var positioned by remember { mutableStateOf(false) }
    var requestPending by remember { mutableStateOf(true) }
    val restoreId = remember { returnId }
    val target = rows.indexOfFirst { it.id == restoreId }.coerceAtLeast(0)
    LaunchedEffect(rows, query) {
        if (!positioned && rows.isEmpty()) searchFocus.requestFocus()
        if (!positioned && rows.isNotEmpty()) {
            grid.scrollToItem(target)
            positioned = true
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 28.sp)
            Box(Modifier.width(240.dp).focusRequester(searchFocus)) { Field("Kanal ara", query, search) }
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Fixed(4), state = grid,
            modifier = Modifier.weight(1f).testTag("live-channels").focusRestorer().focusGroup(),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (rows.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                Text("Eşleşen içerik yok.", color = Ink.muted)
            }
            items(rows.size, key = { rows[it].id }) { index ->
                val item = rows[index]
                if (index == target && positioned && requestPending) LaunchedEffect(Unit) {
                    withFrameNanos { }
                    focus.requestFocus()
                    requestPending = false
                    restored()
                }
                ChannelCard(model, item, now, { play(item, rows) }, focused,
                    Modifier.fillMaxWidth()
                        .then(if (index == target) Modifier.focusRequester(focus) else Modifier),
                    number = numbers[item.id], operations = { open(item) })
            }
            if (rows.size >= limit) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                FocusCard(more, Modifier.fillMaxWidth()) {
                    Text("Devamını göster", Modifier.padding(12.dp))
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
    val next = slots.firstOrNull { it.start >= (current?.end ?: now) }
    val logo by produceState<String?>(null, item.id, item.logo) { value = model.repo.poster(item) }
    FocusCard(play, modifier.fillMaxWidth().height(compactHeight ?: 128.dp)
        .testTag("live-channel-${item.id}").semantics { contentDescription = item.title; this.selected = selected }
        .onFocusChanged { if (it.isFocused) focus() }, onLongClick = operations) {
        Column(Modifier.fillMaxSize().padding(
            horizontal = if (compactHeight != null) 4.dp else 12.dp,
            vertical = if (compactHeight != null) 3.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight != null) 1.dp else 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (selected) "▶ $number" else number.toString(), color = Ink.muted, fontSize = 12.sp, lineHeight = 14.sp)
                Box(Modifier.size(if (compactHeight != null) 20.dp else 28.dp), contentAlignment = Alignment.Center) {
                    Text(item.title.take(1), color = Ink.accent, fontSize = 15.sp, lineHeight = 18.sp)
                    if (logo != null) AsyncImage(logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                Text(item.title, fontSize = 15.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(current?.let { "${it.title} · ${(it.end - now + 59_999) / 60_000} dk" }
                ?: "Program bilgisi yok", fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProgressLine(current?.let { (now - it.start).toFloat() / (it.end - it.start).coerceAtLeast(1) } ?: 0f)
            if (compactHeight == null) Text("Sonra: ${next?.title ?: "—"}", color = Ink.muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
