package dev.lumenara.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.lumenara.data.Item
import dev.lumenara.design.*
import kotlinx.coroutines.flow.collect

/** Collected only by composed channel cards/rows, never for the whole catalog. */
@Composable
internal fun liveProgrammes(
    model: LibraryModel,
    item: Item,
    now: Long,
    horizon: Long = 1,
): List<dev.lumenara.data.Slot> {
    val slots by remember(model.repo, item.source, item.id) {
        model.repo.cardProgrammes(item.source, item.id)
    }.collectAsStateWithLifecycle(emptyList())
    return remember(slots, now, horizon) {
        slots.filter { it.end > now && it.start < now + horizon }.take(160)
    }
}

@Composable
internal fun ChannelCard(
    model: LibraryModel,
    item: Item,
    now: Long,
    play: () -> Unit,
    focus: (Item) -> Unit,
    modifier: Modifier = Modifier.width(200.dp),
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
    val logo by produceState<String?>(null, item.id, item.logo) { value = model.repo.poster(item) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        FocusCard(
            onClick = { restoreFocus = true; play() },
            onLongClick = operations?.let { action -> { restoreFocus = true; action() } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRequester(requester)
                .testTag("live-channel-${item.id}")
                .semantics { contentDescription = item.title }
                .onFocusChanged { if (it.isFocused) focus(item) },
        ) {
            ChannelLogo(model, item, logo, Modifier.fillMaxSize())
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
            if (number != null) Text(number.toString(), style = LumenType.meta)
            Text(item.title, style = LumenType.cardTitle, color = LumenColors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.iconText)) {
            Text(current?.title.orEmpty(), Modifier.weight(1f), style = LumenType.meta, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (current != null) Text(channelTime.format(java.time.Instant.ofEpochMilli(current.start)),
                style = LumenType.meta)
        }
        // Reserve the same space when EPG is missing, keeping the next row aligned.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (current != null) ProgressLine(
                (now - current.start).toFloat() / (current.end - current.start).coerceAtLeast(1),
                Modifier.height(2.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        Text(title.uppercase(java.util.Locale.forLanguageTag("tr")), style = LumenType.caption,
            color = LumenColors.textSecondary)
        LazyRow(
            Modifier.testTag("channel-shelf-$title").focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap),
            contentPadding = PaddingValues(vertical = Spacing.unit),
        ) {
            items(rows, key = { it.id }) { item ->
                ChannelCard(model, item, now, { play(item, rows) }, focus)
            }
        }
    }
}

// Channel-specific fallback and image loading stay local to D3; Artwork is owned by D1.
private val channelTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    .withZone(java.time.ZoneId.systemDefault())
private val channelTones = listOf(
    Color(0xFF1E2430), Color(0xFF2A1F24), Color(0xFF1F2A24),
    Color(0xFF2B2619), Color(0xFF24202E), Color(0xFF1C2A2B),
)

@Composable
internal fun ChannelLogo(model: LibraryModel, item: Item, logo: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sources by model.sources.collectAsStateWithLifecycle()
    val permitted = sources?.firstOrNull { it.id == item.source }?.allowHttp == true
    var measured by remember { mutableStateOf(IntSize.Zero) }
    var loaded by remember(item.id, logo, permitted) { mutableStateOf(false) }
    val request = remember(context, logo, measured, permitted) {
        logo?.takeIf { it.isNotBlank() && measured.width > 0 && measured.height > 0 }?.let {
            artworkRequest(context, it, permitted,
                (measured.width * .7f).toInt(), (measured.height * .7f).toInt())
        }
    }
    val tone = channelTones[Math.floorMod(item.title.hashCode(), channelTones.size)]
    Box(modifier.onSizeChanged { measured = it }.clip(RoundedCornerShape(Radii.channelLogo))
        .background(if (loaded) LumenColors.surface3 else tone), contentAlignment = Alignment.Center) {
        if (!loaded) Text(item.title.trim().split(Regex("\\s+")).take(2).joinToString(" "),
            Modifier.fillMaxWidth(.85f), style = LumenType.label,
            color = LumenColors.textPrimary, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
        AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(.7f),
            contentScale = ContentScale.Fit, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
