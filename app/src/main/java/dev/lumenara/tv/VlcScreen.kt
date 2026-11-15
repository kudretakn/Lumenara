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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.*
import dev.lumenara.data.Item
import dev.lumenara.data.nextItem
import dev.lumenara.data.seriesOrder
import dev.lumenara.design.Ink
import dev.lumenara.design.*
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import dev.lumenara.catalog.*
import dev.lumenara.vlc.VlcInput
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.lumenara.vlc.VlcRuntime
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
    subtitle: androidx.media3.common.MediaItem.SubtitleConfiguration?,
    attachSubtitle: (androidx.media3.common.MediaItem.SubtitleConfiguration?) -> Unit,
    select: (Item, List<Item>) -> Unit,
    next: () -> Unit,
    sleepTimer: dev.lumenara.catalog.SleepTimer,
    sleepTimerVersion: Int,
    onSleepTimerChanged: () -> Unit,
    episodeEnded: () -> Unit,
) {
    val sleepCountdown = sleepTimerVersion.let { sleepTimer.showCountdown() }
    val life by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var panel by remember { mutableStateOf(false) }
    LaunchedEffect(sleepCountdown) { if (sleepCountdown) panel = false }
    var position by remember(item.id) { mutableLongStateOf(initial) }
    val fullscreen = rememberFullscreenState(item)
    val moveScope = rememberCoroutineScope()
    var moveJob by remember { mutableStateOf<Job?>(null) }
    val activeId by rememberUpdatedState(item.id)
    DisposableEffect(item.id) { onDispose { moveJob?.cancel() } }
    val playbackMutex = remember { Mutex() }
    fun move(delta: Int) {
        if (item.kind == "LIVE" && group is LiveChannelGroup) {
            moveJob?.cancel()
            moveJob = moveScope.launch {
                liveNeighbor(model, item, group, delta)?.let { (channel, rows) ->
                    if (activeId == item.id) { Perf.mark("channel"); select(channel, rows) }
                }
            }
            return
        }
        val rows = if (item.kind == "EPISODE") group.seriesOrder() else group
        val target = if (item.kind == "EPISODE" && delta == 1) rows.nextItem(item)
            else rows.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let { rows.getOrNull(it + delta) }
        target?.let { select(it, rows) }
    }
    BackHandler { if (panel) panel = false else if (!fullscreen.dismiss()) back() }
    if (!life.isAtLeast(Lifecycle.State.STARTED)) return
    val numbers = if (item.kind == "LIVE") rememberLiveNumberControl(model, item.source, item.id, select) else null
    val retry = rememberLiveRetry(item.id)
    val context = LocalContext.current
    val composeView = LocalView.current
    val focus = remember { FocusRequester() }
    // Publishing the view from AndroidView.factory changes the playback effect's
    // key from null to view, cancelling playback that may already have started.
    val layout = remember(context) { VLCVideoLayout(context) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var frameRate by remember(item.id, retry.generation) { mutableStateOf<Float?>(null) }
    LaunchedEffect(player, item.id, retry.generation) {
        val p = player ?: return@LaunchedEffect
        while (frameRate == null && player === p && !p.isReleased) {
            val media = p.media
            try {
                frameRate = (0 until (media?.trackCount ?: 0)).asSequence()
                    .mapNotNull { media?.getTrack(it) as? org.videolan.libvlc.interfaces.IMedia.VideoTrack }
                    .filter { it.frameRateNum > 0 && it.frameRateDen > 0 }
                    .map { it.frameRateNum.toFloat() / it.frameRateDen }
                    .firstOrNull()
            } finally { media?.release() }
            delay(500)
        }
    }
    var message by remember { mutableStateOf("") }
    var subtitleNotice by remember(item.id) { mutableStateOf("") }
    val subtitleSearch = rememberSubtitleSearchState(model, item, status = { subtitleNotice = it }) {
        attachSubtitle(it)
        panel = false
    }
    var delayMs by remember(item.id) { mutableLongStateOf(0) }
    var preferenceKey by remember(item.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var audioTracks by remember(item.id) { mutableStateOf(emptyList<MediaPlayer.TrackDescription>()) }
    var textTracks by remember(item.id) { mutableStateOf(emptyList<MediaPlayer.TrackDescription>()) }
    var languages by remember(item.id) { mutableStateOf(emptyMap<Int, String>()) }
    LaunchedEffect(item.id) {
        preferenceKey = model.repo.preferenceKey(item)
        delayMs = model.prefs.text("delay_$preferenceKey", "0").first().toLongOrNull() ?: 0
    }
    var nativePlaying by remember(item.id) { mutableStateOf(false) }
    var mediaGeneration by remember { mutableIntStateOf(0) }
    val currentSubtitle by rememberUpdatedState(subtitle)
    var activeSubtitle by remember(player, mediaGeneration, subtitle?.uri) { mutableStateOf(false) }
    var attached by remember(player, mediaGeneration, subtitle?.uri) { mutableStateOf<android.net.Uri?>(null) }
    // Resolution belongs to the subtitle, not the asynchronous player lifetime.
    // Keep a copied content URI alive across input/player recreation.
    var slaveUri by remember(subtitle?.uri, subtitle?.mimeType) { mutableStateOf<android.net.Uri?>(null) }
    var slaveAttempts by remember(player, mediaGeneration, subtitle?.uri) { mutableIntStateOf(0) }
    var tracksBeforeSlave by remember(player, mediaGeneration, subtitle?.uri) { mutableStateOf(emptySet<Int>()) }
    var slaveTrack by remember(player, mediaGeneration, subtitle?.uri) { mutableStateOf<Int?>(null) }
    var awaitingSlaveSelection by remember(player, mediaGeneration, subtitle?.uri) { mutableStateOf(false) }
    var slaveAddedAt by remember(player, mediaGeneration, subtitle?.uri) { mutableLongStateOf(0) }
    // Capture the same player used as the effect key. A coroutine must not read
    // a newly published player while still holding the previous input's state.
    val subtitlePlayer = player
    val inputGeneration = mediaGeneration
    LaunchedEffect(subtitlePlayer, subtitle?.uri) {
        if (subtitle == null) subtitlePlayer?.let { if (it.spuTrack != -1) it.setSpuTrack(-1) }
    }
    LaunchedEffect(context, subtitle?.uri, subtitle?.mimeType) {
        val configuration = subtitle ?: run {
            android.util.Log.d("LumenaraVlc", "subtitle resolve skipped: no subtitle")
            return@LaunchedEffect
        }
        android.util.Log.d("LumenaraVlc", "subtitle resolve start scheme=${configuration.uri.scheme}")
        var cached: java.io.File? = null
        try {
            slaveUri = if (configuration.uri.scheme == "content") withContext(Dispatchers.IO) {
                val extension = when (configuration.mimeType) {
                    androidx.media3.common.MimeTypes.TEXT_SSA -> ".ass"
                    androidx.media3.common.MimeTypes.TEXT_VTT -> ".vtt"
                    else -> ".srt"
                }
                val file = java.io.File.createTempFile("vlc-subtitle-", extension, context.cacheDir)
                cached = file
                context.contentResolver.openInputStream(configuration.uri).use { input ->
                    requireNotNull(input) { "Subtitle content unavailable" }
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                android.net.Uri.fromFile(file)
            } else configuration.uri
            android.util.Log.d("LumenaraVlc", "subtitle resolve ready scheme=${slaveUri?.scheme}")
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.d("LumenaraVlc", "subtitle resolve failed scheme=${configuration.uri.scheme} cause=${e.javaClass.simpleName}")
        } finally {
            android.util.Log.d("LumenaraVlc", "subtitle resolve disposed cached=${cached != null}")
            cached?.delete()
        }
    }
    fun refreshSlave(p: MediaPlayer) {
        if (p !== player) return
        val tracks = p.spuTracks?.toList().orEmpty()
        textTracks = tracks
        // addSlave belongs to the current input, not the MediaPlayer. An input
        // can lose its slave without replacing the Java player or subtitle URI.
        if (slaveTrack != null && tracks.none { it.id == slaveTrack }) {
            android.util.Log.d("LumenaraVlc", "subtitle track lost id=$slaveTrack; reattach")
            attached = null
            slaveTrack = null
            tracksBeforeSlave = emptySet()
            slaveAttempts = 0
            awaitingSlaveSelection = false
        }
        if (attached != null) {
            if (slaveTrack == null) {
                slaveTrack = tracks.lastOrNull { it.id >= 0 && it.id !in tracksBeforeSlave }?.id
            }
            slaveTrack?.let { id ->
                if (awaitingSlaveSelection && p.spuTrack != id) {
                    val selected = p.setSpuTrack(id)
                    android.util.Log.d("LumenaraVlc", "subtitle select id=$id result=$selected")
                }
                if (p.spuTrack == id) awaitingSlaveSelection = false
            }
        }
        val selected = p.spuTrack
        activeSubtitle = attached != null && slaveTrack != null && selected == slaveTrack
        android.util.Log.d("LumenaraVlc", "subtitle spuCount=${tracks.count { it.id >= 0 }} selected=$selected")
    }
    val refreshCurrentSlave by rememberUpdatedState<(MediaPlayer) -> Unit> { refreshSlave(it) }
    val resolvedSlaveUri = slaveUri
    LaunchedEffect(subtitlePlayer, inputGeneration, subtitle?.uri, resolvedSlaveUri, nativePlaying) {
        val p = subtitlePlayer
        val uri = resolvedSlaveUri
        android.util.Log.d("LumenaraVlc", "subtitle attach effect player=${p != null} generation=$mediaGeneration subtitle=${subtitle != null} resolved=${uri != null} eventPlaying=$nativePlaying")
        if (p == null || uri == null) return@LaunchedEffect
        var decision = ""
        // The playback effect owns playbackMutex until disposal. Never acquire
        // it here: attachment must run while that input is still playing.
        wallClockTicks(250).collect { now ->
            if (p !== player || inputGeneration != mediaGeneration) return@collect
            refreshCurrentSlave(p)
            // A successful addSlave only queues the request; confirm an actual ES.
            if (attached != null && slaveTrack == null && now - slaveAddedAt >= 2000) {
                android.util.Log.d("LumenaraVlc", "subtitle attach timed out waiting for track attempt=$slaveAttempts")
                attached = null
                awaitingSlaveSelection = false
            }
            // Playing can precede effect startup or be followed by Opening.
            // Query this input instead of relying on the last delivered event.
            val playing = p.isPlaying
            val nextDecision = when {
                !playing -> "waiting for native playback (eventPlaying=$nativePlaying)"
                attached != null -> "attached track=$slaveTrack active=$activeSubtitle"
                slaveAttempts >= 3 -> "attempts exhausted"
                else -> "adding slave"
            }
            if (decision != nextDecision) {
                decision = nextDecision
                android.util.Log.d("LumenaraVlc", "subtitle attach decision=$decision generation=$mediaGeneration")
            }
            if (playing && attached == null && slaveAttempts < 3) {
                tracksBeforeSlave = p.spuTracks?.map { it.id }?.toSet().orEmpty()
                slaveAttempts++
                val added = p.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, uri, true)
                android.util.Log.d("LumenaraVlc", "addSlave result=$added scheme=${uri.scheme} attempt=$slaveAttempts")
                if (added) {
                    slaveAddedAt = now
                    attached = subtitle?.uri
                    awaitingSlaveSelection = true
                }
                refreshCurrentSlave(p)
            }
        }
    }
    LaunchedEffect(subtitlePlayer, inputGeneration, subtitle?.uri) {
        val p = subtitlePlayer ?: return@LaunchedEffect
        val key = model.repo.preferenceKey(item)
        var audioSignature = ""
        var textSignature = ""
        wallClockTicks(500).collect {
            if (p !== player || inputGeneration != mediaGeneration) return@collect
            val audio = p.audioTracks?.toList().orEmpty()
            val text = p.spuTracks?.toList().orEmpty()
            val currentAudio = audio.joinToString { "${it.id}:${it.name}" }
            val currentText = text.joinToString { "${it.id}:${it.name}" }
            val audioChanged = currentAudio != audioSignature
            if (audioChanged || currentText != textSignature) {
                audioSignature = currentAudio
                textSignature = currentText
                audioTracks = audio
                textTracks = text
                val media = p.media
                try {
                    languages = (0 until (media?.trackCount ?: 0)).mapNotNull { index ->
                        media?.getTrack(index)?.let { track -> track.id to track.language.orEmpty() }
                    }.toMap()
                } finally { media?.release() }
                val savedAudio = model.prefs.playbackChoice("audio", key)
                val savedText = model.prefs.playbackChoice("text", key)
                val chosenAudio = audio.filter { trackPreferenceRank(savedAudio, languages[it.id], it.id.toString()) < 2 }
                    .minByOrNull { trackPreferenceRank(savedAudio, languages[it.id], it.id.toString()) }
                    ?: audio.firstOrNull { isTurkish(languages[it.id]) || it.name.contains("Turkish", true) || it.name.contains("Türkçe", true) }
                    ?: audio.firstOrNull { it.id >= 0 }
                // A subtitle ES change must not reapply the audio default.
                if (audioChanged) chosenAudio?.let { if (p.audioTrack != it.id) p.setAudioTrack(it.id) }
                val dubbed = productionName(item.title, item.year).version == AudioVersion.DUBBED ||
                    chosenAudio?.let { isTurkish(languages[it.id]) || it.name.contains("Turkish", true) || it.name.contains("Türkçe", true) } == true
                // An explicitly attached subtitle takes precedence over automatic language defaults.
                if (currentSubtitle == null) {
                    if (subtitlesDisabled(savedText, turkishAudio = dubbed, dubbed = false)) {
                        if (p.spuTrack != -1) p.setSpuTrack(-1)
                    } else {
                        val chosenText = text.filter { trackPreferenceRank(savedText, languages[it.id], it.id.toString()) < 2 }
                            .minByOrNull { trackPreferenceRank(savedText, languages[it.id], it.id.toString()) }
                            ?: text.firstOrNull { isTurkish(languages[it.id]) || it.name.contains("Türkçe", true) || it.name.contains("Turkish", true) }
                        val id = chosenText?.id ?: -1
                        if (p.spuTrack != id) p.setSpuTrack(id)
                    }
                }
            }
            activeSubtitle = attached != null && slaveTrack != null && p.spuTrack == slaveTrack
            if (nativePlaying && p.spuDelay != delayMs * 1000) p.setSpuDelay(delayMs * 1000)
        }
    }
    LaunchedEffect(subtitleSearch.open, layout, panel, fullscreen.guide, fullscreen.finished, player, retry.generation, retry.reconnecting, sleepCountdown) {
        if (!subtitleSearch.open && !panel && !fullscreen.guide && !fullscreen.finished && !sleepCountdown) {
            if (!composeView.hasFocus()) composeView.requestFocus()
            val initialGranted = focus.requestFocus()
            android.util.Log.d("LumenaraFocus", "VLC trigger=layout/panel/fullscreen/retry initialGranted=$initialGranted viewHasFocus=${composeView.hasFocus()}")
            // Reclaim Compose focus after native reattachment and reconnect badge removal.
            withFrameNanos { }
            if (!composeView.hasFocus()) composeView.requestFocus()
            val granted = focus.requestFocus()
            android.util.Log.d("LumenaraFocus", "VLC trigger=reattach/retry granted=$granted viewHasFocus=${composeView.hasFocus()}")
        }
    }
    LaunchedEffect(layout, item.id, retry.generation) {
        val surface = layout
        nativePlaying = false
        message = ""
        val playbackScope = this
        android.util.Log.d("LumenaraVlc", "playback waiting for mutex retry=${retry.generation}")
        playbackMutex.withLock {
            android.util.Log.d("LumenaraVlc", "playback acquired mutex")
            var input: VlcInput? = null
            var engine: MediaPlayer? = null
            var completed = false
            try {
                val source = model.repo.library.source(item.source) ?: error("Kaynak kaldırıldı")
                val address = model.repo.address(item)
                val mark = model.repo.library.mark(item.id)
                val start =
                    if (position >= 0) position
                    else mark?.let {
                        if (item.kind == "EPISODE") dev.lumenara.catalog.resumePosition(it.position, it.duration)
                        else it.position.takeIf { position -> it.duration <= 0 || position < it.duration * .95 } ?: 0
                    } ?: 0
                withContext(Dispatchers.IO) {
                    input = VlcInput.open(context, address, source.allowHttp)
                    // Native initialization and media setup can block for seconds.
                    val lib = VlcRuntime.get(context)
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
                    // Listener registration creates a Handler; UI writes stay on Main.
                    // Install it before attaching views, which can start playback.
                    p.setEventListener { event ->
                        playbackScope.launch(Dispatchers.Main.immediate) {
                            if (p !== player) return@launch
                            when (event.type) {
                                MediaPlayer.Event.Opening -> {
                                    nativePlaying = false
                                    mediaGeneration++
                                    android.util.Log.d("LumenaraVlc", "playback Opening generation=$mediaGeneration")
                                }
                                MediaPlayer.Event.Playing -> {
                                    nativePlaying = true
                                    android.util.Log.d("LumenaraVlc", "playback Playing generation=$mediaGeneration")
                                    retry.playing()
                                    message = ""
                                    if (!recorded) {
                                        recorded = true
                                        model.started(item)
                                    }
                                }
                                MediaPlayer.Event.Vout -> {
                                    if (event.voutCount > 0) Perf.since("channel", "channel_first_frame_vlc")
                                    refreshCurrentSlave(p)
                                }
                                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                                    refreshCurrentSlave(p)
                                }
                                MediaPlayer.Event.EncounteredError -> {
                                    nativePlaying = false
                                    if (item.kind != "LIVE" || !retry.failed()) {
                                        message = "VLC bu yayını oynatamadı."
                                        panel = true
                                    }
                                }
                                MediaPlayer.Event.EndReached -> {
                                    nativePlaying = false
                                    completed = true
                                    model.progress(item, p.length, p.length)
                                    if (item.kind == "EPISODE") {
                                        fullscreen.finished = group.nextItem(item) != null
                                        episodeEnded()
                                    }
                                }
                                MediaPlayer.Event.Stopped -> {
                                    nativePlaying = false
                                    android.util.Log.d("LumenaraVlc", "playback Stopped generation=$mediaGeneration")
                                }
                            }
                        }
                    }
                    player = p
                    p.attachViews(surface, null, true, false)
                    // libVLC may also call play() from its surface-created callback.
                    // Serialize that callback and the initial request on Main.
                    p.play()
                    android.util.Log.d("LumenaraVlc", "playback play requested generation=$mediaGeneration")
                }
                wallClockTicks(5000).collect {
                    if (p.isPlaying) model.progress(item, p.time, p.length)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LumenaraVlc", "VLC playback failed: ${e.javaClass.simpleName}")
                if (item.kind != "LIVE" || e !is java.io.IOException || !retry.failed()) {
                    message = "VLC bu kaynakta TS, MKV veya konumlandırılabilir MP4 dosyası gerektirir."
                    panel = true
                }
            } finally {
                withContext(NonCancellable) {
                    val p = engine
                    withContext(Dispatchers.Main.immediate) {
                        android.util.Log.d("LumenaraVlc", "playback cleanup current=${player === p} generation=$mediaGeneration")
                        if (player === p) {
                            player = null
                            nativePlaying = false
                        }
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
                                    model.progress(item, if (completed && length > 0) length else lastPosition, length)
                                }
                            }
                        } finally {
                            // Close the input before stop to unblock native reads.
                            try { input?.close() } finally {
                                try { p?.stop() } finally {
                                    p?.release()
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
            .testTag("vlc-player")
            .semantics {
                contentDescription = "Tam ekran oynatıcı"
                if (activeSubtitle) stateDescription = "İndirilen altyazı etkin"
            }
            .onPreviewKeyEvent {
                if (subtitleSearch.open || sleepCountdown) return@onPreviewKeyEvent false
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
                layout.apply {
                    // VLC adds native SurfaceViews after attachViews; keep remote focus in Compose.
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
            },
            update = {
                it.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                it.isFocusable = false
                it.isFocusableInTouchMode = false
            },
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        )
        if (!panel && !sleepCountdown) FullscreenOverlay(model, item, group, fullscreen, select, { panel = true },
            { player?.time ?: 0 }, { player?.length ?: 0 },
            if (item.kind == "EPISODE" && group.nextItem(item) != null) next else null)
        FrameRateMatch(model, item.kind, frameRate, layout)
        Reconnecting(retry)
        numbers?.let { NumberEntry(it) }
        SubtitleNotice(subtitleNotice) { subtitleNotice = "" }
        SleepTimerCard(sleepTimer, sleepTimerVersion) {
            sleepTimer.cancel()
            onSleepTimerChanged()
        }
        val optionsState = rememberLazyListState()
        LaunchedEffect(panel) { if (!panel) optionsState.scrollToItem(0) }
        if (panel && !subtitleSearch.open) {
            val first = remember { FocusRequester() }
            var audioExpanded by remember { mutableStateOf(false) }
            var textExpanded by remember { mutableStateOf(false) }
            val audioFocus = remember { FocusRequester() }
            val textFocus = remember { FocusRequester() }
            BackHandler(audioExpanded || textExpanded) {
                if (textExpanded) { textExpanded = false; textFocus.requestFocus() }
                else { audioExpanded = false; audioFocus.requestFocus() }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                if (!subtitleSearch.restore) first.requestFocus()
            }
            LazyColumn(
                state = optionsState,
                modifier = Modifier.align(Alignment.CenterEnd).width(400.dp).fillMaxHeight().background(LumenColors.surface1).testTag("player-options").padding(horizontal = Spacing.titleGap, vertical = Spacing.screenVertical)
                    .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text("Seçenekler", style = LumenType.title)
                    Text(playbackDisplayTitle(item), style = LumenType.cardTitle, maxLines = 2)
                    if (message.isNotEmpty()) Text(message, color = Ink.accent)
                }
                item { PlaybackSection("OYNATICI") }
                item { SleepTimerChoices(sleepTimer, item.kind == "EPISODE", onSleepTimerChanged) }
                item {
                    PlayerOption(modifier = Modifier.focusRequester(first), onClick = { panel = false }) {
                        Text("Yayına dön")
                    }
                }
                if (message.isNotEmpty()) item {
                    PlayerOption(onClick = { message = ""; panel = false; retry.manual() }) { Text("Yeniden dene") }
                }
                item {
                    PlayerOption(onClick = { exo(player?.time ?: position) }) { Text("Motor: VLC/Media3") }
                }
                item { VersionChoice(model, item) { version ->
                    scope.launch {
                        val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                            player?.let { it.time.coerceAtLeast(0) to it.length.coerceAtLeast(0) }
                        }
                        if (snapshot != null)
                            model.repo.updateMark(item.id) { it.copy(position = snapshot.first, duration = snapshot.second) }
                        val rows = if (version.kind == "EPISODE") model.repo.episodeSnapshot(version.parent).seriesOrder() else group
                        select(version, rows)
                    }
                } }
                item { PlaybackSection("ALTYAZI") }
                item { SubtitleSearch(subtitleSearch) }
                item { Subtitles { configuration -> scope.launch {
                    model.prefs.set("text_$preferenceKey", if (configuration == null) "off" else "tr")
                    attachSubtitle(configuration)
                } } }
                player?.let { p ->
                    if (item.introStartMs >= 0)
                        item {
                            IntroSkip(item, { p.time }, { p.length }) {
                                p.setTime(item.introEndMs)
                                panel = false
                            }
                        }
                    item { PlaybackSection("GÖRÜNTÜ") }
                    item {
                        PlayerOption(onClick = { if (p.isPlaying) p.pause() else p.play() }) {
                            Text("Duraklat / devam")
                        }
                    }
                    if (p.isSeekable)
                        item {
                            Column {
                                PlayerOption(onClick = { p.setTime((p.time - 10000).coerceAtLeast(0)) }) {
                                    Text("−10 sn")
                                }
                                PlayerOption(onClick = { p.setTime(p.time + 10000) }) { Text("+10 sn") }
                            }
                        }
                    item { PlaybackSection("SES") }
                    item {
                        PlayerOption(onClick = { audioExpanded = !audioExpanded },
                            modifier = Modifier.focusRequester(audioFocus), icon = LumenIcon.Audio) {
                            Text("Ses", Modifier.weight(1f))
                            Text(audioTracks.firstOrNull { it.id == p.audioTrack }?.name.orEmpty(),
                                style = LumenType.meta, maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                    if (audioExpanded) items(audioTracks) { track ->
                        PlayerOption(onClick = {
                            p.setAudioTrack(track.id)
                            audioExpanded = false
                            audioFocus.requestFocus()
                            scope.launch { model.prefs.set("audio_$preferenceKey", trackChoice(languages[track.id], track.id.toString())) }
                        }) {
                            Text("Ses · ${languageName(languages[track.id])} · ${track.name}")
                        }
                    }
                    item { PlaybackSection("ALTYAZI") }
                    item {
                        PlayerOption(onClick = { textExpanded = !textExpanded },
                            modifier = Modifier.focusRequester(textFocus), icon = LumenIcon.Subtitles) {
                            Text("Altyazı", Modifier.weight(1f))
                            Text(textTracks.firstOrNull { it.id == p.spuTrack }?.name ?: "Kapalı",
                                style = LumenType.meta, maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                    if (textExpanded) items(textTracks) { track ->
                        PlayerOption(onClick = {
                            p.setSpuTrack(track.id)
                            textExpanded = false
                            textFocus.requestFocus()
                            scope.launch { model.prefs.set("text_$preferenceKey", if (track.id < 0) "off" else trackChoice(languages[track.id], track.id.toString())) }
                        }) {
                            Text(if (track.id < 0) "Altyazıyı kapat" else "Altyazı · ${languageName(languages[track.id])} · ${track.name}")
                        }
                    }
                    item {
                        Text("Altyazı gecikmesi · $delayMs ms")
                        Column {
                            for (step in listOf(-500L, 500L)) PlayerOption(
                                onClick = {
                                    delayMs = (delayMs + step).coerceIn(-60_000, 60_000)
                                    scope.launch { model.prefs.set("delay_$preferenceKey", delayMs.toString()) }
                                    p.setSpuDelay(delayMs * 1000)
                                }
                            ) {
                                Text(if (step < 0) "−0,5 sn" else "+0,5 sn")
                            }
                        }
                    }
                }
            }
        }
        SubtitleResults(subtitleSearch, panel, focus)
    }
}
