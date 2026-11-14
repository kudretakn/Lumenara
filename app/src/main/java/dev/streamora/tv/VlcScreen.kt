package dev.streamora.tv

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
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.*
import dev.streamora.data.Item
import dev.streamora.design.Ink
import dev.streamora.vlc.VlcInput
import kotlinx.coroutines.*
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
    next: () -> Unit,
) {
    val life by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var panel by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(initial) }
    BackHandler { if (panel) panel = false else back() }
    if (!life.isAtLeast(Lifecycle.State.STARTED)) return
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var layout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var message by remember { mutableStateOf("") }
    var delayMs by remember { mutableLongStateOf(0) }
    LaunchedEffect(panel) { if (!panel) focus.requestFocus() }
    LaunchedEffect(layout) {
        val surface = layout ?: return@LaunchedEffect
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
            }
            val lib = LibVLC(context, arrayListOf("--quiet", "--no-video-title-show"))
            library = lib
            val p = MediaPlayer(lib)
            engine = p
            player = p
            p.attachViews(surface, null, true, false)
            val media = Media(lib, input!!.descriptor.fileDescriptor)
            media.addOption(":demux=" + input!!.demux)
            if (start > 0 && item.kind != "LIVE") media.addOption(":start-time=${start/1000.0}")
            p.media = media
            media.release()
            var recorded = false
            p.setEventListener {
                when (it.type) {
                    MediaPlayer.Event.Playing -> if (!recorded) {
                        recorded = true
                        model.started(item)
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        message = "VLC bu yayını oynatamadı."
                        panel = true
                    }
                    MediaPlayer.Event.EndReached -> {
                        model.progress(item, p.length, p.length)
                        next()
                    }
                }
            }
            p.play()
            while (true) {
                delay(5000)
                if (p.isPlaying) model.progress(item, p.time, p.length)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message = "VLC bu kaynakta TS, MKV veya konumlandırılabilir MP4 dosyası gerektirir."
            panel = true
        } finally {
            player = null
            engine?.let {
                position = it.time
                model.progress(item, position, it.length)
                it.setEventListener(null)
                input?.close()
                it.stop()
                it.detachViews()
                it.release()
            } ?: input?.close()
            library?.release()
        }
    }
    Box(
        Modifier.fillMaxSize()
            .background(Ink.background)
            .focusRequester(focus)
            .onPreviewKeyEvent {
                val key = it.nativeKeyEvent
                if (
                    !panel &&
                        key.keyCode in
                            listOf(
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_MENU,
                            )
                ) {
                    if (key.action == KeyEvent.ACTION_UP) panel = true
                    true
                } else if (key.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    if (key.action == KeyEvent.ACTION_UP)
                        player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                    true
                } else false
            }
            .focusable()
    ) {
        AndroidView(
            factory = { VLCVideoLayout(it).also { view -> layout = view } },
            modifier = Modifier.fillMaxSize(),
        )
        if (panel) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.requestFocus() }
            LazyColumn(
                Modifier.width(330.dp).fillMaxHeight().background(Ink.secondary).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text(item.title)
                    if (message.isNotEmpty()) Text(message, color = Ink.accent)
                }
                item {
                    Button(modifier = Modifier.focusRequester(first), onClick = { panel = false }) {
                        Text("Yayına dön")
                    }
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
