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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import dev.streamora.data.*
import dev.streamora.design.Ink
import dev.streamora.player.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun Video(
    model: LibraryModel,
    item: Item,
    modifier: Modifier,
    preview: Boolean = false,
    multi: Boolean = false,
    ready: (ExoPlayer?) -> Unit = {},
    ended: () -> Unit = {},
    initial: Long = -1,
    decoderFailure: (Long) -> Unit = {},
    subtitle: MediaItem.SubtitleConfiguration? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    if (!lifecycle.isAtLeast(Lifecycle.State.STARTED)) return
    val currentReady by rememberUpdatedState(ready)
    val currentEnded by rememberUpdatedState(ended)
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var resume by remember { mutableLongStateOf(initial) }
    val size by remember { model.prefs.text("size", "Orta") }.collectAsStateWithLifecycle("Orta")
    val color by remember {
        model.prefs.text("color", "Beyaz")
    }
        .collectAsStateWithLifecycle("Beyaz")
    LaunchedEffect(item.id, attempt, subtitle) {
        withContext(Dispatchers.Main.immediate) {
            error = ""
            try {
                val source = model.repo.library.source(item.source) ?: error("Kaynak kaldırıldı")
                val url = model.repo.address(item)
                val saved = model.repo.library.mark(item.id)
                val start =
                    if (resume >= 0) resume
                    else
                        saved?.position?.takeIf {
                            saved.duration <= 0 || it < saved.duration * 0.95
                        } ?: 0
                val engine =
                    createPlayer(
                        context,
                        source.allowHttp,
                        !multi,
                        model.prefs.text("buffer", "Dengeli").first(),
                        subtitle?.uri,
                    )
                val language = model.prefs.text("sub", "Kapalı").first()
                engine.trackSelectionParameters =
                    engine.trackSelectionParameters
                        .buildUpon()
                        .setPreferredAudioLanguage(model.prefs.text("audio", "tr").first())
                        .setPreferredTextLanguage(if (language == "Kapalı") null else language)
                        .setTrackTypeDisabled(
                            C.TRACK_TYPE_TEXT,
                            language == "Kapalı" && subtitle == null,
                        )
                        .build()
                player = engine
                currentReady(engine)
                engine.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(cause: PlaybackException) {
                            error = playbackMessage(cause)
                            model.diagnostic("Media3 ${cause.errorCode}")
                            if (cause.errorCode in 4001..4006)
                                decoderFailure(engine.currentPosition)
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) {
                                model.progress(item, engine.duration, engine.duration)
                                currentEnded()
                            }
                        }
                    }
                )
                val media = MediaItem.Builder().setUri(url)
                if (subtitle != null) media.setSubtitleConfigurations(listOf(subtitle))
                engine.setMediaItem(media.build(), if (item.kind == "LIVE") 0 else start)
                engine.prepare()
                engine.playWhenReady = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    val frames =
                        e.stackTrace.take(8).joinToString(" ← ") {
                            "${it.className}.${it.methodName}:${it.lineNumber}"
                        }
                    android.util.Log.w("StreamoraPlayback", "${e.javaClass.simpleName} · $frames")
                }
                error = "Yayın adresi açılamadı. Kaynağı yenileyin."
            }
        }
    }
    DisposableEffect(item.id, attempt, subtitle) {
        onDispose {
            player?.let { engine ->
                resume = engine.currentPosition
                model.progress(item, resume, engine.duration)
                engine.release()
                player = null
                currentReady(null)
            }
        }
    }
    LaunchedEffect(player) {
        val engine = player ?: return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
            while (true) {
                delay(5000)
                if (engine.isPlaying) model.progress(item, engine.currentPosition, engine.duration)
            }
        }
    }
    Box(modifier.background(androidx.compose.ui.graphics.Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = !preview && item.kind != "LIVE"
                    isFocusable = useController
                    keepScreenOn = true
                }
            },
            update = {
                it.player = player
                it.subtitleView?.setFractionalTextSize(
                    when (size) {
                        "Küçük" -> 0.04f
                        "Büyük" -> 0.075f
                        else -> 0.055f
                    }
                )
                it.subtitleView?.setApplyEmbeddedStyles(false)
                it.subtitleView?.setStyle(
                    androidx.media3.ui.CaptionStyleCompat(
                        if (color == "Sarı") android.graphics.Color.YELLOW
                        else android.graphics.Color.WHITE,
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        android.graphics.Color.BLACK,
                        null,
                    )
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (error.isNotEmpty())
            Column(Modifier.align(Alignment.Center).background(Ink.secondary).padding(12.dp)) {
                Text(error, maxLines = 3)
                Button(onClick = { attempt++ }) { Text("Yeniden dene") }
            }
    }
}

@Composable
internal fun Playback(
    model: LibraryModel,
    item: Item,
    group: List<Item>,
    select: (Item) -> Unit,
    back: () -> Unit,
    changeGroup: (List<Item>) -> Unit,
) {
    var panel by remember { mutableStateOf(false) }
    var multi by remember { mutableStateOf(emptyList<Item>()) }
    var hint by remember { mutableStateOf("") }
    var operations by remember { mutableStateOf(false) }
    var channelQuery by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(80) }
    val slots = remember { decoderSlots() }
    val scope = rememberCoroutineScope()
    if (multi.isNotEmpty()) {
        MultiView(
            model,
            multi,
            group,
            { channel ->
                multi = emptyList()
                select(channel)
            },
            { multi = emptyList() },
        )
        return
    }
    var engine by remember { mutableStateOf<ExoPlayer?>(null) }
    val focus = remember { FocusRequester() }
    val live = item.kind == "LIVE"
    var subtitle by remember { mutableStateOf<MediaItem.SubtitleConfiguration?>(null) }
    fun move(delta: Int) {
        val at = group.indexOfFirst { it.id == item.id }
        group.getOrNull(at + delta)?.let(select)
    }
    val preferred by remember {
        model.prefs.text("engine", "Media3")
    }
        .collectAsStateWithLifecycle(null)
    val fallback by remember {
        model.prefs.text("fallback", "Açık")
    }
        .collectAsStateWithLifecycle("Açık")
    if (preferred == null) return
    var vlc by remember { mutableStateOf(preferred == "VLC") }
    var transferred by remember { mutableLongStateOf(-1) }
    var switched by remember { mutableStateOf(false) }
    if (vlc) {
        VlcScreen(
            model,
            item,
            transferred,
            { pos ->
                transferred = pos
                switched = true
                vlc = false
            },
            back,
        ) {
            if (item.kind == "EPISODE") move(1)
        }
        return
    }
    BackHandler { if (panel) panel = false else back() }
    LaunchedEffect(panel) { if (!panel) focus.requestFocus() }
    Box(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .semantics { contentDescription = "Tam ekran oynatıcı" }
            .onPreviewKeyEvent {
                val key = it.nativeKeyEvent
                val code = key.keyCode
                val ok =
                    code in
                        listOf(
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                        )
                if (
                    !panel &&
                        (ok ||
                            code == KeyEvent.KEYCODE_MENU ||
                            live &&
                                code in
                                    listOf(
                                        KeyEvent.KEYCODE_DPAD_UP,
                                        KeyEvent.KEYCODE_DPAD_DOWN,
                                        KeyEvent.KEYCODE_DPAD_LEFT,
                                        KeyEvent.KEYCODE_DPAD_RIGHT,
                                    ))
                ) {
                    if (key.action == KeyEvent.ACTION_UP) {
                        when (code) {
                            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
                            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
                            else -> {
                                operations = ok && key.eventTime - key.downTime >= 500
                                panel = true
                            }
                        }
                    }
                    true
                } else if (
                    code in
                        listOf(
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE,
                        )
                ) {
                    if (key.action == KeyEvent.ACTION_UP)
                        engine?.let { p ->
                            if (
                                code == KeyEvent.KEYCODE_MEDIA_PLAY ||
                                    code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !p.isPlaying
                            )
                                p.play()
                            else p.pause()
                        }
                    true
                } else false
            }
            .focusable()
    ) {
        Video(
            model,
            item,
            Modifier.fillMaxSize(),
            ready = { engine = it },
            initial = transferred,
            subtitle = subtitle,
            decoderFailure = { pos ->
                if (!switched && fallback == "Açık") {
                    switched = true
                    transferred = pos
                    vlc = true
                }
            },
            ended = { if (item.kind == "EPISODE") move(1) },
        )
        if (panel) {
            val candidates by
                remember(item.source, category, channelQuery, limit) {
                        model.repo.library.browse(
                            item.source,
                            "LIVE",
                            category,
                            dev.streamora.catalog.searchable(channelQuery),
                            "",
                            limit,
                        )
                    }
                    .collectAsStateWithLifecycle(emptyList())
            val categories by
                remember(item.source) { model.repo.library.categories("LIVE", item.source) }
                    .collectAsStateWithLifecycle(emptyList())
            val close = remember { FocusRequester() }
            LaunchedEffect(Unit) { close.requestFocus() }
            val mark by
                remember(item.id) { model.repo.library.observeMark(item.id) }
                    .collectAsStateWithLifecycle(null)
            LazyColumn(
                Modifier.width(310.dp).fillMaxHeight().background(Ink.secondary).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                item {
                    Text(item.title, fontSize = 20.sp)
                    Text(item.category, color = Ink.muted)
                }
                item {
                    Button(onClick = { panel = false }, modifier = Modifier.focusRequester(close)) {
                        Text("Yayına dön")
                    }
                }
                item {
                    Button(onClick = { model.mark(item, true) }) {
                        Text(if (mark?.favorite == true) "Favoriden çıkar" else "Favoriye ekle")
                    }
                }
                item { Tracks(engine) }
                if (item.introStartMs >= 0)
                    item {
                        engine?.let { p ->
                            IntroSkip(item, { p.currentPosition }, { p.duration }) {
                                p.seekTo(item.introEndMs)
                                panel = false
                            }
                        }
                    }
                if (live)
                    item {
                        var guide by remember { mutableStateOf(false) }
                        Button(onClick = { guide = !guide }) { Text("Kanal rehberi") }
                        if (guide)
                            GuidePanel(model, item) { replay ->
                                changeGroup(listOf(replay))
                                select(replay)
                            }
                    }
                item {
                    Subtitles {
                        subtitle = it
                        panel = false
                    }
                }
                if (!live)
                    item {
                        Button(
                            onClick = {
                                engine?.let { if (it.isPlaying) it.pause() else it.play() }
                            }
                        ) {
                            Text("Duraklat / devam")
                        }
                        Row {
                            for (step in listOf(-10000, 10000)) Button(
                                onClick = {
                                    engine?.let { e ->
                                        e.seekTo((e.currentPosition + step).coerceAtLeast(0))
                                    }
                                }
                            ) {
                                Text(if (step < 0) "−10 sn" else "+10 sn")
                            }
                        }
                    }
                item {
                    Button(
                        onClick = {
                            transferred = engine?.currentPosition ?: -1
                            vlc = true
                        }
                    ) {
                        Text("VLC ile aç")
                    }
                }
                item {
                    if (live)
                        for (count in listOf(2, 4)) Button(
                            onClick = {
                                scope.launch {
                                    val options = (listOf(item) + group).distinctBy { it.id }
                                    val limits =
                                        options
                                            .map { it.source }
                                            .distinct()
                                            .associateWith { model.prefs.connections(it).first() }
                                    val selection =
                                        dev.streamora.catalog.multiSelection(
                                            count,
                                            slots,
                                            options,
                                            limits,
                                        ) {
                                            it.source
                                        }
                                    if (selection.entries.isEmpty()) hint = selection.message
                                    else {
                                        panel = false
                                        multi = selection.entries
                                    }
                                }
                            }
                        ) {
                            Text("$count kanallı görünüm")
                        }
                    if (hint.isNotEmpty()) Text(hint)
                    val e = engine
                    if (e != null && e.isCurrentMediaItemLive && e.isCurrentMediaItemSeekable) {
                        Button(
                            onClick = { e.seekTo((e.currentPosition - 30_000).coerceAtLeast(0)) }
                        ) {
                            Text("30 sn geri")
                        }
                        Button(onClick = { if (e.isPlaying) e.pause() else e.play() }) {
                            Text("Duraklat / devam")
                        }
                        Button(onClick = { e.seekToDefaultPosition() }) { Text("Canlıya dön") }
                    }
                }
                if (live && !operations)
                    item {
                        Field("Kanal ara", channelQuery, { channelQuery = it })
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            items(listOf("") + categories, key = { it }) { name ->
                                Button(onClick = { category = name }) {
                                    Text(
                                        (if (category == name) "✓ " else "") +
                                            name.ifEmpty { "Tümü" }
                                    )
                                }
                            }
                        }
                    }
                val list =
                    if (live && !operations) candidates else if (operations) emptyList() else group
                items(list, key = { it.id }) { channel ->
                    Button(
                        onClick = {
                            if (channel.id == item.id) panel = false
                            else {
                                changeGroup(list)
                                select(channel)
                            }
                        }
                    ) {
                        Text(channel.title)
                    }
                }
                if (live && candidates.size >= limit && !operations)
                    item { Button(onClick = { limit += 80 }) { Text("Devamını göster") } }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Tracks(player: ExoPlayer?) {
    if (player == null) return
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onTracksChanged(value: Tracks) {
                    tracks = value
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    for (group in
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT }) {
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            val label = if (group.type == C.TRACK_TYPE_AUDIO) "Ses" else "Altyazı"
            Button(
                onClick = {
                    player.trackSelectionParameters =
                        player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(group.type, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                }
            ) {
                Text("$label · " + (format.label ?: format.language ?: (i + 1).toString()))
            }
        }
    }
    if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT })
        Button(
            onClick = {
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
            }
        ) {
            Text("Altyazıyı kapat")
        }
}
