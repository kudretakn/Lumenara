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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
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
    var timeline by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable(source) { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(source, category, query) { mutableIntStateOf(80) }
    // Filters can reuse an already focused lazy row without another focus event.
    // Keep its identity so resetting the debounce does not lose that focus.
    var focusedId by remember(source) { mutableStateOf<String?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    val listState = rememberSaveable(source, category, query, saver = LazyListState.Saver) { LazyListState() }
    val categoryState = rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    var returnIndex by remember(returnToChannel) { mutableIntStateOf(-1) }
    LaunchedEffect(returnToChannel) {
        val target = returnToChannel ?: return@LaunchedEffect
        timeline = false
        if (target != focusedId) {
            // The mini guide may have selected a channel outside the saved filter/page.
            category = "all"
            query = ""
            val index = model.repo.library.browse(source, "LIVE", "", "", "", Int.MAX_VALUE)
                .first().indexOfFirst { it.id == target }
            if (index >= 0) returnIndex = index
            else onChannelFocusRestored()
        }
    }
    LaunchedEffect(returnIndex, category, query) {
        if (returnIndex >= 0 && category == "all" && query.isEmpty())
            limit = maxOf(limit, returnIndex + 1)
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        wallClockTicks(30_000).collect { now = it }
    }
    val groups by remember(source) { model.repo.library.liveCategoryCounts(source) }
        .collectAsStateWithLifecycle(emptyList())
    val rows by remember(source, category, query, limit) {
        if (category == "recent") model.prefs.recentLive.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else model.repo.library.liveByIds(source, ids).map { items ->
                val byId = items.associateBy { it.id }
                ids.mapNotNull { byId[it] }.filter { it.search.contains(searchable(query)) }
            }
        } else model.repo.library.browse(
            source, "LIVE", category.removePrefix("provider:").takeIf { category.startsWith("provider:") }.orEmpty(),
            searchable(query), if (category == "favorite") "favorite" else "", limit,
        )
    }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(returnToChannel, rows) {
        val index = rows.indexOfFirst { it.id == returnToChannel }
        if (returnToChannel != null && index >= 0) listState.scrollToItem(index)
    }
    // A new focus cancels the previous delay; loading more rows does not restart playback.
    LaunchedEffect(focusedId, source, category, query, timeline) {
        previewId = null
        if (!timeline && focusedId != null) {
            val target = focusedId
            delay(600)
            previewId = target
        }
    }
    if (timeline) {
        Timeline(model, source, open, play) { timeline = false }
        return
    }
    val preview = rows.firstOrNull { it.id == previewId }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("live-screen").onPreviewKeyEvent { numbers.key(it.nativeKeyEvent) }) {
        // At 960 dp, safe areas and the navigation rail leave 816 dp for all three columns.
        val categoryWidth = minOf(200.dp, maxWidth * .22f)
        val channelWidth = minOf(420.dp, maxWidth * .44f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassPanel(Modifier.width(categoryWidth).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(
                        Modifier.weight(1f).testTag("live-categories").focusRestorer().focusGroup(),
                        state = categoryState,
                        contentPadding = PaddingValues(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(listOf("recent" to "Son izlenenler", "favorite" to "Favoriler",
                            "all" to "Tümü"), key = { it.first }) { (id, title) ->
                            Pill(title, category == id, { category = id }, Modifier.fillMaxWidth())
                        }
                        items(groups.filter { it.category.isNotEmpty() }, key = { "provider:${it.category}" }) { group ->
                            val id = "provider:${group.category}"
                            Pill("${group.category} (${group.count})", category == id,
                                { category = id }, Modifier.fillMaxWidth().testTag("live-category-${group.category}"))
                        }
                    }
                    FocusCard({ timeline = true }, Modifier.fillMaxWidth()) {
                        Text("Yayın çizelgesi", Modifier.padding(12.dp), fontSize = 15.sp)
                    }
                }
            }
            Column(Modifier.width(channelWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Kanal ara", query, { query = it })
                LazyColumn(
                    Modifier.weight(1f).testTag("live-channels").focusRestorer().focusGroup(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (rows.isEmpty()) item { Text("Eşleşen içerik yok.", color = Ink.muted) }
                    items(rows, key = { it.id }) { item ->
                        val restoreFocus = item.id == returnToChannel
                        if (restoreFocus) LaunchedEffect(item.id) {
                            // Let the enclosing page finish its initial content focus request.
                            withFrameNanos { }
                            returnFocus.requestFocus()
                            onChannelFocusRestored()
                        }
                        LiveChannelRow(model, item, channelNumbers[item.id] ?: 1, now,
                            { play(item, rows) }, { open(item) }, { focusedId = item.id; numberSource = item.source },
                            if (restoreFocus) Modifier.focusRequester(returnFocus) else Modifier)
                    }
                    if (category != "recent" && rows.size >= limit) item {
                        FocusCard({ limit += 80 }, Modifier.fillMaxWidth()) {
                            Text("Devamını göster", Modifier.padding(12.dp), fontSize = 15.sp)
                        }
                    }
                }
            }
            GlassPanel(Modifier.weight(1f).fillMaxHeight().testTag("live-preview-panel")) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (preview == null) Text("Önizleme için bir kanalda bekleyin.", color = Ink.muted, fontSize = 15.sp)
                    else key(preview.id) {
                        Video(model, preview, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .testTag("live-preview"), preview = true)
                        val slots = liveProgrammes(model, preview, now)
                        val current = slots.firstOrNull { now >= it.start && now < it.end }
                        Text(preview.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current?.title ?: "Program bilgisi yok", fontSize = 20.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        current?.let {
                            Text(slotTime(it), color = Ink.accent, fontSize = 12.sp)
                            Text(it.description, Modifier.weight(1f, fill = false),
                                color = Ink.muted, fontSize = 15.sp, maxLines = 5,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Text("Tam ekran için OK", color = Ink.muted, fontSize = 12.sp)
                    }
                }
            }
        }
        NumberEntry(numbers)
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

private fun slotTime(slot: Slot): String {
    val format = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    return "${format.format(Instant.ofEpochMilli(slot.start))} – ${format.format(Instant.ofEpochMilli(slot.end))}"
}
