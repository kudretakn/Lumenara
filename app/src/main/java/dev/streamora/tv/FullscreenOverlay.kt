package dev.streamora.tv

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.streamora.data.Item
import dev.streamora.design.*
import kotlinx.coroutines.flow.collect

/** Shared remote contract and real-time deadlines for both playback engines. */
@Stable
internal class FullscreenState {
    var guide by mutableStateOf(false)
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
        bannerUntil > now -> { bannerUntil = 0; true }
        else -> false
    }
    fun key(key: KeyEvent, live: Boolean, move: (Int) -> Unit, options: () -> Unit,
            seek: (Long) -> Unit): Boolean {
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
        if (!ok && code !in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return false
        if (!live && code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return false
        if (key.action == KeyEvent.ACTION_UP) when {
            code == KeyEvent.KEYCODE_MENU || ok && key.eventTime - key.downTime >= 500 -> options()
            live && code == KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            live && code == KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
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
    position: () -> Long, duration: () -> Long, next: (() -> Unit)?,
) {
    if (state.guide && item.kind == "LIVE") {
        MiniGuide(model, item, state, select, options)
    } else if (state.finished && next != null) {
        GlassPanel(Modifier.align(Alignment.BottomEnd).padding(48.dp, 27.dp).width(320.dp)) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            FocusCard(next, Modifier.padding(16.dp).fillMaxWidth().focusRequester(focus)) {
                Text("Sonraki bölüm  ▶", Modifier.padding(20.dp), fontSize = 20.sp)
            }
        }
    } else if (state.bannerUntil > state.now) {
        LaunchedEffect(item.id, state.bannerRequest) {
            // The first callback precedes drawing; the next frame confirms that
            // the banner had a chance to draw, even after a stalled UI thread.
            withFrameNanos { }
            withFrameNanos { }
            state.now = System.currentTimeMillis()
            state.bannerUntil = state.now + 4000
        }
        GlassPanel(Modifier.align(Alignment.BottomCenter).padding(horizontal = 48.dp, vertical = 27.dp)
            .fillMaxWidth().testTag(if (item.kind == "LIVE") "fullscreen-banner" else "vod-timeline")) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.kind == "LIVE") {
                    val slots = liveProgrammes(model, item, state.now / 30_000 * 30_000, 86_400_000)
                    val current = slots.firstOrNull { state.now >= it.start && state.now < it.end }
                    val following = slots.firstOrNull { it.start >= (current?.end ?: state.now) }
                    val logo by produceState<String?>(null, item.id) { value = model.repo.poster(item) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${(group.indexOfFirst { it.id == item.id } + 1).coerceAtLeast(1)}", color = Ink.muted)
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Text(item.title.take(1), color = Ink.accent)
                            if (logo != null) AsyncImage(logo, null, Modifier.fillMaxSize())
                        }
                        Text(item.title, fontSize = 28.sp, maxLines = 1)
                    }
                    Text(current?.title ?: "Program bilgisi yok", fontSize = 20.sp, maxLines = 1)
                    ProgressLine(current?.let { (state.now - it.start).toFloat() / (it.end - it.start).coerceAtLeast(1) } ?: 0f)
                    Text("Sonra: ${following?.title ?: "—"}", color = Ink.muted, maxLines = 1)
                    Text("OK / ← Mini rehber    •    Uzun OK / Menü: Seçenekler", fontSize = 12.sp, color = Ink.muted)
                } else {
                    val elapsed = position().coerceAtLeast(0)
                    val total = duration().coerceAtLeast(0)
                    Text(item.title, fontSize = 28.sp, maxLines = 1)
                    ProgressLine(if (total > 0) elapsed.toFloat() / total else 0f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(playbackTime(elapsed)); Text("−${playbackTime((total - elapsed).coerceAtLeast(0))}")
                    }
                    Text("← −10 sn    •    +10 sn →    •    OK: Seçenekler", color = Ink.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun playbackTime(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@Composable
private fun MiniGuide(model: LibraryModel, playing: Item, state: FullscreenState,
                      select: (Item, List<Item>) -> Unit, options: () -> Unit) {
    var category by remember { mutableStateOf("") }
    val categories by remember(playing.source) { model.repo.library.categories("LIVE", playing.source) }
        .collectAsStateWithLifecycle(emptyList())
    val rows by key(playing.source, category) {
        remember(playing.source, category) {
            model.repo.library.browse(playing.source, "LIVE", category, "", "", Int.MAX_VALUE)
        }.collectAsStateWithLifecycle(emptyList())
    }
    val list = rememberLazyListState()
    val currentFocus = remember(category) { FocusRequester() }
    val target = rows.indexOfFirst { it.id == playing.id }.coerceAtLeast(0)
    var positioned by remember(category) { mutableStateOf(false) }
    LaunchedEffect(rows, category) {
        if (rows.isNotEmpty() && !positioned) { list.scrollToItem(target); positioned = true }
    }
    GlassPanel(Modifier.fillMaxWidth(.42f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
        .fillMaxHeight().testTag("mini-guide").onPreviewKeyEvent {
            state.activity = System.currentTimeMillis(); false
        }) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mini rehber", fontSize = 18.sp)
                Pill("Seçenekler", false, { state.guide = false; options() })
            }
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                items((listOf("") + categories).distinct(), key = { it }) { name ->
                    Pill(name.ifEmpty { "Tümü" }, category == name, { category = name })
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // TV commonly exposes 540 dp vertically: shrink below 80 dp to keep eight rows visible.
                val rowHeight = minOf(80.dp, (maxHeight - 16.dp - 4.dp * 7) / 8)
                LazyColumn(Modifier.fillMaxSize().testTag("mini-guide-channels"), state = list,
                    verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(8.dp)) {
                    itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                        val isTarget = index == target
                        if (isTarget && positioned) LaunchedEffect(category, positioned) { currentFocus.requestFocus() }
                        LiveChannelRow(model, row, index + 1, state.now / 30_000 * 30_000,
                            { state.guide = false; state.showBanner(); select(row, rows) },
                            { state.guide = false; options() },
                            { state.activity = System.currentTimeMillis() },
                            if (isTarget) Modifier.focusRequester(currentFocus) else Modifier,
                            selected = row.id == playing.id, compactHeight = rowHeight)
                    }
                }
            }
        }
    }
}
