package dev.lumenara.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private class CardFocusObserver {
    var focused = false
    var notifiedId: String? = null
    fun notify(item: Item?, focus: (Item) -> Unit) {
        if (focused && item != null && notifiedId != item.id) {
            notifiedId = item.id
            focus(item)
        }
    }
}

/** Notify once per acquisition, including a focused placeholder becoming a real card. */
@Composable
internal fun rememberCardFocus(item: Item?, focus: (Item) -> Unit): (Boolean) -> Unit {
    val observer = remember { CardFocusObserver() }
    SideEffect { observer.notify(item, focus) }
    return { focused ->
        observer.focused = focused
        if (!focused) observer.notifiedId = null
        observer.notify(item, focus)
    }
}

@Composable
internal fun ChannelCard(
    model: LibraryModel,
    item: Item?,
    now: Long,
    play: () -> Unit,
    focus: (Item) -> Unit,
    modifier: Modifier = Modifier.width(200.dp),
    number: Int? = null,
    operations: (() -> Unit)? = null,
    compact: Boolean = false,
    categoryName: String? = null,
    currentProgramme: dev.lumenara.data.Slot? = null,
    compactLogo: String? = null,
    compactAllowHttp: Boolean = false,
    compactLogoSize: Int = 0,
) {
    val row = item ?: Item("", "", "", "", "LIVE", "", "", "", 0, "")
    val observeFocus = rememberCardFocus(item, focus)
    var restoreFocus by rememberSaveable(row.id) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val restoring = remember { restoreFocus }
    if (restoring) LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
        restoreFocus = false
    }
    val current = if (compact) currentProgramme else {
        val slots = if (item == null) emptyList() else liveProgrammes(model, item, now)
        slots.firstOrNull { now >= it.start && now < it.end }
    }
    if (compact) {
        val name = remember(row.title, categoryName) { channelCardName(row.title, categoryName) }
        FocusCard(
            focusedScale = 1f,
            onClick = { item?.let { focus(it); restoreFocus = true; play() } },
            onLongClick = operations?.let { action -> { restoreFocus = true; action() } },
            modifier = modifier.height(60.dp).focusRequester(requester)
                .testTag("live-channel-${row.id}")
                .semantics { contentDescription = row.title }
                .onFocusChanged { observeFocus(it.isFocused) },
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().padding(horizontal = Spacing.unit),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    ChannelLogo(model, row, compactLogo, Modifier.size(40.dp), compact = true,
                        allowHttp = compactAllowHttp, decodeSize = compactLogoSize)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f),
                                style = LumenType.label, color = LumenColors.textPrimary,
                                maxLines = if (current != null) 1 else 2,
                                overflow = TextOverflow.Ellipsis)
                            if (number != null) Text(number.toString(), style = LumenType.meta)
                        }
                        if (current != null) Text(current.title, style = LumenType.meta,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (current != null) ProgressLine(
                    (now - current.start).toFloat() / (current.end - current.start).coerceAtLeast(1),
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp))
            }
        }
        return
    }
    val logo by produceState<String?>(null, row.id, row.logo) { value = item?.let { model.repo.poster(it) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        FocusCard(
            onClick = { item?.let { focus(it); restoreFocus = true; play() } },
            onLongClick = operations?.let { action -> { restoreFocus = true; action() } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRequester(requester)
                .testTag("live-channel-${row.id}")
                .semantics { contentDescription = row.title }
                .onFocusChanged { observeFocus(it.isFocused) },
        ) {
            ChannelLogo(model, row, logo, Modifier.fillMaxSize())
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
            if (number != null) Text(number.toString(), style = LumenType.meta)
            Text(row.title, style = LumenType.cardTitle, color = LumenColors.textPrimary,
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

// Channel-specific fallback and image loading stay local to D3; Artwork is owned by D1.
private val channelTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    .withZone(java.time.ZoneId.systemDefault())
private val channelTones = listOf(
    Color(0xFF1E2430), Color(0xFF2A1F24), Color(0xFF1F2A24),
    Color(0xFF2B2619), Color(0xFF24202E), Color(0xFF1C2A2B),
)

@Composable
internal fun ChannelLogo(model: LibraryModel, item: Item, logo: String?, modifier: Modifier = Modifier,
    compact: Boolean = false, allowHttp: Boolean? = null, decodeSize: Int = 0) {
    val context = LocalContext.current
    val permitted = allowHttp ?: run {
        val sources by model.sources.collectAsStateWithLifecycle()
        sources?.firstOrNull { it.id == item.source }?.allowHttp == true
    }
    val measured = if (decodeSize > 0) null else remember { mutableStateOf(IntSize.Zero) }
    val width = if (decodeSize > 0) decodeSize else ((measured?.value?.width ?: 0) * .7f).toInt()
    val height = if (decodeSize > 0) decodeSize else ((measured?.value?.height ?: 0) * .7f).toInt()
    var loaded by remember(item.id, logo, permitted) { mutableStateOf(false) }
    val request = remember(context, logo, width, height, permitted) {
        logo?.takeIf { it.isNotBlank() && width > 0 && height > 0 }?.let {
            artworkRequest(context, it, permitted, width, height)
        }
    }
    val fallback = remember(item.title, compact) {
        if (compact) channelCardInitials(item.title)
        else item.title.trim().split(Regex("\\s+")).take(2).joinToString(" ")
    }
    val tone = channelTones[Math.floorMod(item.title.hashCode(), channelTones.size)]
    Box((if (decodeSize > 0) modifier else modifier.onSizeChanged { measured?.value = it }).clip(RoundedCornerShape(Radii.channelLogo))
        .background(if (loaded) LumenColors.surface3 else tone), contentAlignment = Alignment.Center) {
        if (!loaded) Text(fallback,
            Modifier.fillMaxWidth(.85f), style = LumenType.label,
            color = LumenColors.textPrimary, maxLines = if (compact) 1 else 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
        // A text-only tile must not initialize Coil while the first frame is drawing.
        if (request != null) {
            AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(.7f),
                contentScale = ContentScale.Fit, onSuccess = { loaded = true }, onError = { loaded = false })
        }
    }
}
