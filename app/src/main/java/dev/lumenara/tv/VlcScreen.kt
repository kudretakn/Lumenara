package dev.lumenara.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.*
import dev.lumenara.data.Item
import dev.lumenara.design.Ink
import dev.lumenara.design.glass
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.collect
import dev.lumenara.vlc.VlcInput
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
internal fun VlcScreen(
    model: LibraryModel,
    item: Item,
    initial: Long,
    exo: (Long) -> Unit,
    back: () -> Unit,
    group: List<Item>,
    select: (Item, List<Item>) -> Unit,
    next: () -> Unit,
) {
    val life by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var panel by remember { mutableStateOf(false) }
    var position by remember(item.id) { mutableLongStateOf(initial) }
    val fullscreen = rememberFullscreenState(item)
    fun move(delta: Int) { group.getOrNull(group.indexOfFirst { it.id == item.id } + delta)?.let { select(it, group) } }
    BackHandler { if (panel) panel = false else if (!fullscreen.dismiss()) back() }
    if (!life.isAtLeast(Lifecycle.State.STARTED)) return
    val numbers = if (item.kind == "LIVE") rememberLiveNumberControl(model, item.source, item.id, select) else null
    val retry = rememberLiveRetry(item.id)
    val context = LocalContext.current
    val composeView = LocalView.current
    val focus = remember { FocusRequester() }
    var layout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val playbackMutex = remember { Mutex() }
    var message by remember { mutableStateOf("") }
    var delayMs by remember { mutableLongStateOf(0) }
    LaunchedEffect(layout, panel, fullscreen.guide, fullscreen.finished, player, retry.generation, retry.reconnecting) {
        if (!panel && !fullscreen.guide && !fullscreen.finished) {
            if (!composeView.hasFocus()) composeView.requestFocus()
            val initialGranted = focus.requestFocus()
            android.util.Log.d("LumenaraFocus", "VLC trigger=layout/panel/fullscreen/retry initialGranted=$initialGranted viewHasFocus=${composeView.hasFocus()}")
            if (layout != null) {
                // Reclaim Compose focus after native reattachment and reconnect badge removal.
                withFrameNanos { }
                if (!composeView.hasFocus()) composeView.requestFocus()
                val granted = focus.requestFocus()
                android.util.Log.d("LumenaraFocus", "VLC trigger=reattach/retry granted=$granted viewHasFocus=${composeView.hasFocus()}")
            }
        }
    }
    LaunchedEffect(layout, item.id, retry.generation) {
        val surface = layout ?: return@LaunchedEffect
        message = ""
        val playbackScope = this
        playbackMutex.withLock {
            var input: VlcInput? = null
            var library: LibVLC? = null
            var engine: MediaPlayer? = null
            try {
                val source = model.repo.library.source(item.source) ?: error("Kaynak kaldırıldı")
                val address = model.repo.address(item)
                val mark = model.repo.library.mark(item.id)
                val start =
                    if (position >= 0) position
                    else mark?.position?.takeIf { mark.duration <= 0 || it < mark.duration * 0.95 } ?: 0
                withContext(Dispatchers.IO) {
                    input = VlcInput.open(context, address, source.allowHttp)
                    // Native initialization and media setup can block for seconds.
                    val lib = LibVLC(context, arrayListOf("--quiet", "--no-video-title-show"))
                    library = lib
                    val p = MediaPlayer(lib)
                    engine = p
                    val media = Media(lib, input!!.descriptor.fileDescriptor)
                    try {
                        media.addOption(":demux=" + input!!.demux)
                        if (start > 0 && item.kind != "LIVE") media.addOption(":start-time=${start/1000.0}")
                        p.media = media
                    } finally {
                        media.release()
                    }
                }
                val p = checkNotNull(engine)
                var recorded = false
                withContext(Dispatchers.Main.immediate) {
                    player = p
                    p.attachViews(surface, null, true, false)
                    // Listener registration creates a Handler; UI writes stay on Main.
                    p.setEventListener { event ->
                        playbackScope.launch(Dispatchers.Main.immediate) {
                            when (event.type) {
                                MediaPlayer.Event.Playing -> {
                                    retry.playing()
                                    message = ""
                                    if (!recorded) {
                                        recorded = true
                                        model.started(item)
                                    }
                                }
                                MediaPlayer.Event.EncounteredError -> {
                                    if (item.kind != "LIVE" || !retry.failed()) {
                                        message = "VLC bu yayını oynatamadı."
                                        panel = true
                                    }
                                }
                                MediaPlayer.Event.EndReached -> {
                                    model.progress(item, p.length, p.length)
                                    if (item.kind == "EPISODE")
                                        fullscreen.finished = group.getOrNull(group.indexOfFirst { row -> row.id == item.id } + 1) != null
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.IO) { p.play() }
                wallClockTicks(5000).collect {
                    if (p.isPlaying) model.progress(item, p.time, p.length)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LumenaraVlc", "VLC playback failed", e)
                if (item.kind != "LIVE" || e !is java.io.IOException || !retry.failed()) {
                    message = "VLC bu kaynakta TS, MKV veya konumlandırılabilir MP4 dosyası gerektirir."
                    panel = true
                }
            } finally {
                withContext(NonCancellable) {
                    val p = engine
                    withContext(Dispatchers.Main.immediate) {
                        player = null
                        p?.setEventListener(null)
                        p?.detachViews()
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            p?.let {
                                val lastPosition = it.time
                                val length = it.length
                                withContext(Dispatchers.Main.immediate) {
                                    position = lastPosition
                                    model.progress(item, lastPosition, length)
                                }
                            }
                        } finally {
                            // Close the input before stop to unblock native reads.
                            try { input?.close() } finally {
                                try { p?.stop() } finally {
                                    try { p?.release() } finally { library?.release() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Box(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .focusRequester(focus)
            .semantics { contentDescription = "Tam ekran oynatıcı" }
            .onPreviewKeyEvent {
                val key = it.nativeKeyEvent
                val code = key.keyCode
                if (!panel && fullscreen.key(key, item.kind == "LIVE", ::move, { panel = true },
                        { delta -> player?.let { p -> p.setTime((p.time + delta).coerceAtLeast(0)) } }, { event -> numbers?.key(event) == true })) true
                else if (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)) {
                    if (key.action == KeyEvent.ACTION_UP) player?.let { p ->
                        if (code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !p.isPlaying) p.play() else p.pause()
                    }
                    true
                } else false
            }
            .focusable()
    ) {
        AndroidView(
            factory = {
                VLCVideoLayout(it).apply {
                    // VLC adds native SurfaceViews after attachViews; keep remote focus in Compose.
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    isFocusable = false
                    isFocusableInTouchMode = false
                }.also { view -> layout = view }
            },
            update = {
                it.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                it.isFocusable = false
                it.isFocusableInTouchMode = false
            },
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        )
        if (!panel) FullscreenOverlay(model, item, group, fullscreen, select, { panel = true },
            { player?.time ?: 0 }, { player?.length ?: 0 },
            if (item.kind == "EPISODE" && group.getOrNull(group.indexOfFirst { it.id == item.id } + 1) != null) next else null)
        Reconnecting(retry)
        numbers?.let { NumberEntry(it) }
        if (panel) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.requestFocus() }
            LazyColumn(
                Modifier.width(330.dp).fillMaxHeight().padding(12.dp).glass().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text("Seçenekler", fontSize = 20.sp)
                    Text(item.title)
                    if (message.isNotEmpty()) Text(message, color = Ink.accent)
                }
                item {
                    Button(modifier = Modifier.focusRequester(first), onClick = { panel = false }) {
                        Text("Yayına dön")
                    }
                }
                if (message.isNotEmpty()) item {
                    Button(onClick = { message = ""; panel = false; retry.manual() }) { Text("Yeniden dene") }
                }
                item {
                    Button(onClick = { exo(player?.time ?: position) }) { Text("Media3 ile aç") }
                }
                player?.let { p ->
                    if (item.introStartMs >= 0)
                        item {
                            IntroSkip(item, { p.time }, { p.length }) {
                                p.setTime(item.introEndMs)
                                panel = false
                            }
                        }
                    item {
                        Button(onClick = { if (p.isPlaying) p.pause() else p.play() }) {
                            Text("Duraklat / devam")
                        }
                    }
                    if (p.isSeekable)
                        item {
                            Row {
                                Button(onClick = { p.setTime((p.time - 10000).coerceAtLeast(0)) }) {
                                    Text("−10 sn")
                                }
                                Button(onClick = { p.setTime(p.time + 10000) }) { Text("+10 sn") }
                            }
                        }
                    items(p.audioTracks?.toList().orEmpty()) { track ->
                        Button(onClick = { p.setAudioTrack(track.id) }) {
                            Text("Ses · ${track.name}")
                        }
                    }
                    items(p.spuTracks?.toList().orEmpty()) { track ->
                        Button(onClick = { p.setSpuTrack(track.id) }) {
                            Text("Altyazı · ${track.name}")
                        }
                    }
                    item {
                        Text("Altyazı gecikmesi · $delayMs ms")
                        Row {
                            for (step in listOf(-100L, 100L)) Button(
                                onClick = {
                                    delayMs += step
                                    p.setSpuDelay(delayMs * 1000)
                                }
                            ) {
                                Text(if (step < 0) "−100 ms" else "+100 ms")
                            }
                        }
                    }
                }
            }
        }
    }
}
