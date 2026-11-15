package dev.lumenara.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.lumenara.catalog.searchable
import dev.lumenara.data.Item
import dev.lumenara.design.FocusCard
import dev.lumenara.design.Ink
import dev.lumenara.design.ProgressLine
import kotlinx.coroutines.flow.collect

/** Collected only by composed channel cards/rows, never for the whole catalog. */
@Composable
internal fun liveProgrammes(
    model: LibraryModel,
    item: Item,
    now: Long,
    horizon: Long = 1,
): List<dev.lumenara.data.Slot> {
    val mapping by remember(item.id) { model.repo.guide.mapping(item.id) }
        .collectAsStateWithLifecycle(null)
    val slots by remember(item, mapping, now, horizon) {
        model.repo.guide.guide(
            item.source, mapping?.channel ?: item.tvgId, searchable(item.title),
            now, now + horizon, "", mapping?.feed.orEmpty(),
        )
    }.collectAsStateWithLifecycle(emptyList())
    return slots
}

@Composable
internal fun ChannelCard(
    model: LibraryModel,
    item: Item,
    now: Long,
    play: () -> Unit,
    focus: (Item) -> Unit,
    modifier: Modifier = Modifier.width(240.dp),
    number: Int? = null,
    operations: (() -> Unit)? = null,
) {
    var restoreFocus by rememberSaveable(item.id) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (restoreFocus) {
            withFrameNanos { }
            requester.requestFocus()
            restoreFocus = false
        }
    }
    val slots = liveProgrammes(model, item, now)
    val current = slots.firstOrNull { now >= it.start && now < it.end }
    val logo by produceState<String?>(null, item.logo) { value = model.repo.poster(item) }
    FocusCard(
        onClick = { restoreFocus = true; play() },
        onLongClick = operations?.let { action -> { restoreFocus = true; action() } },
        modifier = modifier.focusRequester(requester).height(128.dp).testTag("live-channel-${item.id}")
            .semantics { contentDescription = item.title }
            .onFocusChanged { if (it.isFocused) focus(item) },
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (logo != null)
                    AsyncImage(logo, null, Modifier.size(36.dp), contentScale = ContentScale.Fit)
                Text((number?.let { "$it · " } ?: "") + item.title, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (current == null) Text("Program bilgisi yok", color = Ink.muted, fontSize = 12.sp)
            if (current != null) {
                Text(current.title, color = Ink.muted, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                ProgressLine((now - current.start).toFloat() / (current.end - current.start))
            }
        }
    }
}

@Composable
internal fun ChannelShelf(
    model: LibraryModel,
    title: String,
    rows: List<Item>,
    play: (Item, List<Item>) -> Unit,
    focus: (Item) -> Unit,
) {
    if (rows.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        wallClockTicks(30_000).collect { now = it }
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, fontSize = 19.sp)
        LazyRow(
            Modifier.testTag("channel-shelf-$title").focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(rows, key = { it.id }) { item ->
                ChannelCard(model, item, now, { play(item, rows) }, focus)
            }
        }
    }
}
