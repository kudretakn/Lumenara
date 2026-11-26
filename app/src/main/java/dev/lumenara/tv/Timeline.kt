package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dev.lumenara.design.LumenColors
import dev.lumenara.catalog.searchable
import dev.lumenara.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun Timeline(
    model: LibraryModel,
    source: String,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
    back: () -> Unit,
) {
    val entry = remember { FocusRequester() }
    var returnChannel by rememberSaveable { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (returnChannel == null) { withFrameNanos { }; entry.requestFocus() } }
    var window by rememberSaveable(stateSaver = Saver<ZonedDateTime, Long>(
        save = { it.toInstant().toEpochMilli() },
        restore = { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) })) {
        mutableStateOf(ZonedDateTime.now().withMinute(0).withSecond(0).withNano(0))
    }
    var query by rememberSaveable { mutableStateOf("") }
    var selection by remember(window, query) { mutableStateOf<Pair<Item, Slot>?>(null) }
    val restoreId = remember { returnChannel }
    val channels = rememberLivePages(model, source, returnId = restoreId)
    var restorePending by remember(channels) { mutableStateOf(true) }
    var positioned by remember(channels) { mutableStateOf(false) }
    LaunchedEffect(channels, channels.anchor, channels.count, channels[channels.anchor ?: 0]) {
        if (!positioned) {
            channels.anchor?.let {
                if (it < channels.count && channels[it] != null) {
                    list.scrollToItem(it)
                    positioned = true
                }
            }
        }
    }
    LaunchedEffect(channels, positioned) {
        if (positioned) snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { it?.let(channels::visible) }
    }
    val from = window.toInstant().toEpochMilli()
    val to = window.plusHours(2).toInstant().toEpochMilli()
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    BackHandler { if (selection == null) back() else selection = null }
    Column(Modifier.focusRequester(entry), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Yayın akışı", fontSize = 28.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Button(onClick = back) { Text("Kanallar") }
            Button(onClick = { window = window.minusDays(1) }) { Text("−1 gün") }
            Button(onClick = { window = window.minusHours(2) }) { Text("‹ 2 sa") }
            Button(
                onClick = { window = ZonedDateTime.now().withMinute(0).withSecond(0).withNano(0) }
            ) {
                Text("Şimdi")
            }
            Button(onClick = { window = window.plusHours(2) }) { Text("2 sa ›") }
            Button(onClick = { window = window.plusDays(1) }) { Text("+1 gün") }
        }
        Field("Program ara", query, { query = it })
        Row {
            Text(
                window.format(DateTimeFormatter.ofPattern("dd.MM")),
                Modifier.width(108.dp),
                color = LumenColors.accent,
            )
            repeat(4) {
                Text(
                    time.format(window.plusMinutes(30L * it)),
                    Modifier.weight(1f),
                    color = LumenColors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        selection?.let { (channel, slot) ->
            Text(
                "${channel.title} · ${time.format(Instant.ofEpochMilli(slot.start))}  ${slot.title}"
            )
            Text(slot.description, color = LumenColors.textSecondary, maxLines = 3)
            if (
                channel.archive.isNotEmpty() &&
                    slot.end <= System.currentTimeMillis() &&
                    slot.start >=
                        System.currentTimeMillis() -
                            channel.archiveDays.coerceIn(0, 365) * 86400000L
            ) {
                Button(
                    onClick = {
                        model.job("Arşiv programı açıldı.") {
                            val replay = model.repo.replay(channel, slot)
                            play(replay, listOf(replay))
                        }
                    }
                ) {
                    Text("Arşivden izle")
                }
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.categoryFirstDraw(channels.firstPageLoaded &&
                (channels.count == 0 || positioned && channels[channels.anchor ?: 0] != null)),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(3.dp),
        ) {
            items(channels.count, key = { it }) { index ->
                val channel = channels[index]
                val channelFocus = remember { FocusRequester() }
                if (channel != null && channel.id == returnChannel && restorePending && positioned) LaunchedEffect(Unit) {
                    withFrameNanos { }; channelFocus.requestFocus(); restorePending = false
                }
                val mapping by
                    remember(channel?.id) { channel?.let { model.repo.guide.mapping(it.id) } ?: flowOf(null) }
                        .collectAsStateWithLifecycle(null)
                val slots by
                    remember(channel?.id, mapping, from, query) {
                            if (channel == null) flowOf(emptyList<Slot>()) else model.repo.guide.guide(
                                channel.source,
                                mapping?.channel ?: channel.tvgId,
                                searchable(channel.title),
                                from,
                                to,
                                searchable(query),
                                mapping?.feed.orEmpty(),
                            )
                        }
                        .collectAsStateWithLifecycle(emptyList())
                if (channel == null || query.isBlank() || slots.isNotEmpty())
                    Row {
                        Surface(
                            onClick = { channel?.let { returnChannel = it.id; open(it) } },
                            modifier = Modifier.focusRequester(channelFocus).width(108.dp).height(35.dp)
                                .onFocusChanged { if (it.isFocused) channels.focused(index) },
                        ) {
                            Text(
                                channel?.title ?: "Yükleniyor",
                                Modifier.padding(5.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BoxWithConstraints(
                            Modifier.weight(1f).height(35.dp).background(LumenColors.surface1)
                        ) {
                            for (slot in slots.distinctBy { it.start }) {
                                val left = (maxOf(from, slot.start) - from).toFloat() / (to - from)
                                val width =
                                    (minOf(to, slot.end) - maxOf(from, slot.start)).toFloat() /
                                        (to - from)
                                Surface(
                                    onClick = { channel?.let { selection = it to slot } },
                                    modifier =
                                        Modifier.onFocusChanged { if (it.isFocused) channels.focused(index) }
                                            .offset(x = maxWidth * left)
                                            .width(maxWidth * width)
                                            .fillMaxHeight()
                                            .padding(horizontal = 1.dp),
                                ) {
                                    Text(
                                        slot.title,
                                        Modifier.padding(4.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                // Zero-height search misses would make LazyColumn compose the whole catalog.
                else Spacer(Modifier.fillMaxWidth().height(35.dp))
            }
        }
    }
}
