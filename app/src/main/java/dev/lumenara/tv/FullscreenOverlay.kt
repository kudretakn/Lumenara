package dev.lumenara.tv

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.lumenara.data.Item
import dev.lumenara.data.nextItem
import dev.lumenara.design.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun liveZapDirection(keyCode: Int, live: Boolean): Int? {
    if (!live) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 1
        else -> null
    }
}

/** Shared remote contract and real-time deadlines for both playback engines. */
@Stable
internal class FullscreenState {
    var guide by mutableStateOf(false)
    // One onboarding use per playback session, shared by both engines and channel switches.
    var hintClaimed by mutableStateOf(false)
    var showLiveHint by mutableStateOf(false)
    var bannerUntil by mutableLongStateOf(0)
    var activity by mutableLongStateOf(0)
    var now by mutableLongStateOf(System.currentTimeMillis())
    var finished by mutableStateOf(false)
    var bannerRequest by mutableLongStateOf(0)
        private set
    fun showBanner() {
        now = System.currentTimeMillis()
        // Keep it visible without expiring until the overlay has rendered.
        bannerUntil = Long.MAX_VALUE
        bannerRequest++
    }
    fun openGuide() { guide = true; activity = System.currentTimeMillis(); bannerUntil = 0 }
    fun dismiss(): Boolean = when {
        guide -> { guide = false; true }
        bannerUntil > now && !finished -> { bannerUntil = 0; true }
        else -> false
    }
    fun key(key: KeyEvent, live: Boolean, move: (Int) -> Unit, options: () -> Unit,
            seek: (Long) -> Unit, liveKey: (KeyEvent) -> Boolean = { false }): Boolean {
        if (live && liveKey(key)) return true
        if (guide) { activity = System.currentTimeMillis(); return false }
        val code = key.keyCode
        if (code in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)) {
            if (key.action == KeyEvent.ACTION_UP) when (code) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> move(1)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> move(-1)
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(10_000); showBanner() }
                else -> { seek(-10_000); showBanner() }
            }
            return true
        }
        if (finished && code != KeyEvent.KEYCODE_MENU &&
            !(key.action == KeyEvent.ACTION_UP && key.eventTime - key.downTime >= 500)) return false
        val ok = code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        val zapDirection = liveZapDirection(code, live)
        if (!ok && code !in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT) && zapDirection == null) return false
        if (key.action == KeyEvent.ACTION_UP) when {
            code == KeyEvent.KEYCODE_MENU || ok && key.eventTime - key.downTime >= 500 -> options()
            zapDirection != null -> move(zapDirection)
            live && (code == KeyEvent.KEYCODE_DPAD_LEFT || ok && bannerUntil > System.currentTimeMillis()) -> openGuide()
            live && ok -> showBanner()
            !live && code == KeyEvent.KEYCODE_DPAD_LEFT -> { seek(-10_000); showBanner() }
            !live && code == KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(10_000); showBanner() }
            else -> options()
        }
        return true
    }
}

@Composable
internal fun rememberFullscreenState(item: Item): FullscreenState {
    val state = remember { FullscreenState() }
    LaunchedEffect(item.id) { state.guide = false; state.finished = false; state.showBanner() }
    LaunchedEffect(state) {
        wallClockTicks(250).collect {
            state.now = it
            if (state.guide && it - state.activity >= 8000) state.guide = false
        }
    }
    return state
}

@Composable
internal fun BoxScope.FullscreenOverlay(
    model: LibraryModel, item: Item, group: List<Item>, state: FullscreenState,
    select: (Item, List<Item>) -> Unit, options: () -> Unit,
    position: Long, duration: Long, next: (() -> Unit)?,
) {
    LaunchedEffect(state, item.kind == "LIVE") {
        if (item.kind == "LIVE" && !state.hintClaimed) {
            hintCounterLock.withLock {
                if (!state.hintClaimed) {
                    val used = model.prefs.text(LIVE_HINT_USES).first().toIntOrNull()?.coerceIn(0, 3) ?: 0
                    state.showLiveHint = used < 3
                    model.prefs.set(LIVE_HINT_USES, (used + 1).coerceAtMost(3).toString())
                    state.hintClaimed = true
                }
            }
        }
    }
    val panelSlidePx = with(LocalDensity.current) { Motion.panelSlide.roundToPx() }
    AnimatedVisibility(state.guide && item.kind == "LIVE",
        enter = slideInHorizontally(tween(Motion.panelEnterMillis)) { -panelSlidePx } +
            fadeIn(tween(Motion.panelEnterMillis)),
        exit = ExitTransition.None) {
        MiniGuide(model, item, state, select, options)
    }
    if (!state.guide && state.finished && next != null) {
        Panel(Modifier.align(Alignment.BottomEnd).padding(Spacing.screenHorizontal, Spacing.screenVertical).width(320.dp)) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            LumenButton(next, Modifier.padding(Spacing.cardGap).fillMaxWidth().focusRequester(focus)) {
                LumenIcon(LumenIcon.Play, contentDescription = null)
                Column {
                    Text("Sonraki bölüm", style = LumenType.cardTitle)
                    group.nextItem(item)?.let { episode ->
                        val name = dev.lumenara.catalog.episodeName(episode.title, episode.category)
                        Text("S${name.season} B${name.episode} · ${name.title}", style = LumenType.meta, maxLines = 2,
                            color = LumenColors.onFocus)
                    }
                }
            }
        }
    }
    val bannerVisible = !state.guide && !(state.finished && next != null) && state.bannerUntil > state.now
    if (bannerVisible) {
        LaunchedEffect(item.id, state.bannerRequest) {
            // Two frames let the banner draw before its wall-clock deadline starts.
            withFrameNanos { }; withFrameNanos { }
            state.now = System.currentTimeMillis()
            state.bannerUntil = state.now + Motion.bannerAutoHideMillis
        }
    }
    AnimatedVisibility(bannerVisible, Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        enter = fadeIn(tween(Motion.bannerFadeMillis)), exit = fadeOut(tween(Motion.bannerFadeMillis))) {
        Box(Modifier.fillMaxWidth().height(200.dp).background(LumenBrushes.scrim)
            .testTag(if (item.kind == "LIVE") "fullscreen-banner" else "vod-timeline")) {
            if (item.kind == "LIVE") {
                LiveBanner(model, item, state)
            } else {
                val elapsed = position.coerceAtLeast(0)
                val total = duration.coerceAtLeast(0)
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.screenVertical),
                    verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    Text(playbackDisplayTitle(item), style = LumenType.title, color = LumenColors.textPrimary, maxLines = 1)
                    ProgressLine(if (total > 0) elapsed.toFloat() / total else 0f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(playbackTime(elapsed), style = LumenType.meta)
                        Text("${playbackTime((total - elapsed).coerceAtLeast(0))} kaldı", style = LumenType.meta)
                    }
                }
            }
        }
    }
}

internal const val LIVE_HINT_USES = "live_hint_uses"
private val hintCounterLock = Mutex()
private val bannerTime = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private fun clockTime(ms: Long): String = bannerTime.format(Instant.ofEpochMilli(ms))

@Composable
private fun BoxScope.LiveBanner(model: LibraryModel, item: Item, state: FullscreenState) {
    val numbers = liveChannelNumbers(model, item.source, listOf(item))
    val slots = liveProgrammes(model, item, state.now / 30_000 * 30_000, 86_400_000)
    val current = slots.firstOrNull { state.now >= it.start && state.now < it.end }
    val following = slots.firstOrNull { it.start >= (current?.end ?: state.now) }
    val logo by produceState<String?>(null, item.id, item.logo) { value = model.repo.poster(item) }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            ChannelLogo(model, item, logo, Modifier.size(96.dp, 54.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    Text("${numbers[item.id] ?: 1}", style = LumenType.meta)
                    Text(item.title, Modifier.weight(1f), style = LumenType.title,
                        color = LumenColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(6.dp).background(LumenColors.live, androidx.compose.foundation.shape.CircleShape))
                    Text("CANLI", style = LumenType.caption, color = LumenColors.live)
                    Text(clockTime(state.now), style = LumenType.meta, color = LumenColors.textPrimary)
                }
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
                        Text(current.title, Modifier.weight(1f), style = LumenType.body,
                            color = LumenColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${clockTime(current.start)} – ${clockTime(current.end)}", style = LumenType.meta)
                    }
                    ProgressLine((state.now - current.start).toFloat() / (current.end - current.start).coerceAtLeast(1),
                        Modifier.widthIn(max = 560.dp))
                }
                if (following != null) Text("Sonra  ${clockTime(following.start)}  ${following.title}",
                    style = LumenType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.showLiveHint) Row(Modifier.align(Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            LumenIcon(LumenIcon.ChevronLeft, contentDescription = null, tint = LumenColors.textSecondary, size = 20.dp)
            Text("Kanallar  ·  Basılı tut: Seçenekler", Modifier.testTag("live-first-use-hint"), style = LumenType.meta)
        }
    }
}

private fun playbackTime(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@Composable
private fun MiniGuide(model: LibraryModel, playing: Item, state: FullscreenState,
                      select: (Item, List<Item>) -> Unit, options: () -> Unit) {
    var category by remember { mutableStateOf("") }
    val categories by remember(playing.source) { model.repo.categories("LIVE", playing.source) }
        .collectAsStateWithLifecycle(emptyList())
    val choices = (listOf("") + categories).distinct()
    fun changeCategory(delta: Int) {
        val index = choices.indexOf(category).coerceAtLeast(0)
        category = choices[Math.floorMod(index + delta, choices.size)]
        state.activity = System.currentTimeMillis()
    }
    val channels = rememberLivePages(model, playing.source, category, returnId = playing.id)
    val numbers = liveChannelNumbers(model, playing.source, channels.loaded())
    val list = rememberLazyListState()
    val currentFocus = remember(channels) { FocusRequester() }
    val target = channels.anchor ?: 0
    var positioned by remember(channels) { mutableStateOf(false) }
    var focusPending by remember(channels) { mutableStateOf(true) }
    LaunchedEffect(channels, channels[target]) {
        if (channels[target] != null && !positioned) { list.scrollToItem(target); positioned = true }
    }
    LaunchedEffect(channels, positioned) {
        if (positioned) snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { it?.let(channels::visible) }
    }
    Panel(Modifier.width(400.dp).fillMaxHeight().testTag("mini-guide").onPreviewKeyEvent {
        state.activity = System.currentTimeMillis(); false
    }, shape = RectangleShape) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.cardGap, vertical = Spacing.screenVertical),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                Text(category.ifEmpty { "Tüm kanallar" }, Modifier.weight(1f), style = LumenType.title,
                    color = LumenColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LumenButton({ changeCategory(-1) }, style = LumenButtonStyle.Secondary) {
                    LumenIcon(LumenIcon.ChevronLeft, contentDescription = "Önceki kategori")
                }
                LumenButton({ changeCategory(1) }, style = LumenButtonStyle.Secondary) {
                    LumenIcon(LumenIcon.ChevronRight, contentDescription = "Sonraki kategori")
                }
                LumenButton({ state.guide = false; options() }, style = LumenButtonStyle.Secondary) {
                    LumenIcon(LumenIcon.Settings, contentDescription = "Seçenekler")
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("mini-guide-channels"), state = list,
                contentPadding = PaddingValues(end = Spacing.unit, bottom = Spacing.screenVertical)) {
                items(channels.count, key = { it }) { index ->
                    val row = channels[index]
                    val isTarget = index == target
                    if (isTarget && positioned && focusPending) LaunchedEffect(category, positioned) {
                        currentFocus.requestFocus(); focusPending = false
                    }
                    LiveChannelRow(model, row, row?.let { numbers[it.id] } ?: (index + 1), state.now / 30_000 * 30_000,
                        { row?.let { Perf.mark("channel"); state.guide = false; state.showBanner(); select(it, channels.loaded()) } },
                        { state.guide = false; options() },
                        { channels.focused(index); state.activity = System.currentTimeMillis() },
                        (if (isTarget) Modifier.focusRequester(currentFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) channels.focused(index) },
                        selected = row?.id == playing.id, compactHeight = 64.dp)
                }
            }
        }
    }
}
