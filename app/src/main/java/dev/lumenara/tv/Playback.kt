package dev.lumenara.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
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
import dev.lumenara.data.*
import dev.lumenara.catalog.displayTitle
import dev.lumenara.catalog.trackChoice
import dev.lumenara.catalog.trackPreferenceRank
import dev.lumenara.design.Ink
import dev.lumenara.design.*
import dev.lumenara.player.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
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
    failure: (Boolean) -> Unit = {},
    liveRetry: LiveRetry? = null,
    subtitleDelayUs: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    if (!lifecycle.isAtLeast(Lifecycle.State.STARTED)) return
    val currentReady by rememberUpdatedState(ready)
    val currentEnded by rememberUpdatedState(ended)
    var playbackActive by remember(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf("") }
    var subtitleText by remember(item.id) { mutableStateOf("") }
    val retry = liveRetry ?: rememberLiveRetry(item.id, preview)
    val attempt = retry.generation
    val currentFailure by rememberUpdatedState(failure)
    LaunchedEffect(error) { currentFailure(error.isNotEmpty()) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var listener by remember { mutableStateOf<Player.Listener?>(null) }
    var configuration by remember { mutableStateOf<Triple<Boolean, Boolean, String>?>(null) }
    val allowedHttp = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val allowedSubtitle = remember { java.util.concurrent.atomic.AtomicReference<android.net.Uri?>(null) }
    var resume by remember(item.id) { mutableLongStateOf(initial) }
    var frameRate by remember(item.id, attempt) { mutableStateOf<Float?>(null) }
    var videoOutput by remember { mutableStateOf<PlayerView?>(null) }
    val size by remember { model.prefs.text("size", "Orta") }.collectAsStateWithLifecycle("Orta")
    val color by remember {
        model.prefs.text("color", "Beyaz")
    }
        .collectAsStateWithLifecycle("Beyaz")
    LaunchedEffect(item.id, item.encryptedUrl, attempt, subtitle) {
        withContext(Dispatchers.Main.immediate) {
            error = ""
            playbackActive = false
            subtitleText = ""
            try {
                val source = model.repo.library.source(item.source) ?: error("Kaynak kaldırıldı")
                val url = model.repo.address(item)
                val saved = model.repo.library.mark(item.id)
                val start =
                    if (resume >= 0) resume
                    else
                        saved?.let {
                            if (item.kind == "EPISODE") dev.lumenara.catalog.resumePosition(it.position, it.duration)
                            else it.position.takeIf { position -> it.duration <= 0 || position < it.duration * .95 } ?: 0
                        } ?: 0
                val profile = model.prefs.text("buffer", "Dengeli").first()
                val config = Triple(item.kind == "LIVE", multi, profile)
                allowedHttp.set(source.allowHttp)
                allowedSubtitle.set(subtitle?.uri)
                if (configuration != config) {
                    player?.release()
                    player = null
                }
                val engine = player ?: createPlayer(
                        context,
                        source.allowHttp,
                        !multi,
                        profile,
                        subtitle?.uri,
                        automaticRetries = item.kind != "LIVE",
                        subtitleDelayUs = subtitleDelayUs,
                        live = item.kind == "LIVE",
                        multi = multi,
                        httpAllowed = { allowedHttp.get() },
                        permittedSubtitle = { allowedSubtitle.get() },
                    )
                configuration = config
                player = engine
                engine.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                val preferenceKey = model.repo.preferenceKey(item)
                val audioPreference = model.prefs.playbackChoice("audio", preferenceKey)
                val textPreference = model.prefs.playbackChoice("text", preferenceKey)
                val dubbed = dev.lumenara.catalog.productionName(item.title, item.year).version == dev.lumenara.catalog.AudioVersion.DUBBED
                engine.trackSelectionParameters = engine.trackSelectionParameters.buildUpon()
                    .clearOverrides()
                    .setPreferredAudioLanguages(audioPreference.ifBlank { "tr" }, "tur")
                    .setPreferredTextLanguages("tr", "tur")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textPreference == "off" || textPreference.isBlank() && dubbed)
                    .build()
                currentReady(engine)
                var recorded = false
                var formats = ""
                val mediaListener = object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            if (!preview && !multi) Perf.since("channel", "channel_first_frame_media3")
                        }
                        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                            subtitleText = cueGroup.cues.mapNotNull { it.text }.joinToString(" ")
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            if (frameRate == null) frameRate = tracks.groups
                                .filter { it.type == C.TRACK_TYPE_VIDEO }
                                .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).frameRate } }
                                .firstOrNull { it.isFinite() && it > 0f }
                            val signature = tracks.groups.joinToString { g -> (0 until g.length).joinToString { g.getTrackFormat(it).toString() } }
                            if (signature == formats) return
                            formats = signature
                            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                            val audio = audioGroups
                                .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
                            if (audioGroups.isNotEmpty() && audio.isEmpty()) {
                                // Media3 plays video silently when no audio track can be decoded
                                // (e.g. MPEG-L2 or AC-3 on devices without a decoder); VLC can.
                                val mimes = audioGroups.flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).sampleMimeType } }
                                    .distinct().joinToString()
                                model.diagnostic("Media3 unsupported audio: $mimes")
                                error = "Bu yayının ses biçimi ($mimes) Media3 ile çözülemiyor. Ayarlar'dan VLC'yi seçebilirsiniz."
                                decoderFailure(engine.currentPosition)
                            }
                            val requested = audio.filter { (g, i) -> trackPreferenceRank(audioPreference, g.getTrackFormat(i).language, g.getTrackFormat(i).id) < 2 }
                                .minByOrNull { (g, i) -> trackPreferenceRank(audioPreference, g.getTrackFormat(i).language, g.getTrackFormat(i).id) }
                            val turkish = audio.firstOrNull { (g, i) -> dev.lumenara.catalog.isTurkish(g.getTrackFormat(i).language) }
                            val original = audio.firstOrNull { (g, i) -> g.getTrackFormat(i).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 } ?: audio.firstOrNull()
                            val selectedAudio = requested ?: turkish ?: original
                            val disableText = dev.lumenara.catalog.subtitlesDisabled(textPreference,
                                selectedAudio?.let { (g, i) -> dev.lumenara.catalog.isTurkish(g.getTrackFormat(i).language) } == true, dubbed)
                            val params = engine.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disableText)
                            selectedAudio?.let { (g, i) -> params.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) }
                            tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
                                (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it }
                            }.let { texts ->
                                val selectedText = texts.firstOrNull { (g, i) -> textPreference == "downloaded" &&
                                    g.getTrackFormat(i).label == "Türkçe · İndirilen altyazı" }
                                    ?: texts.filter { (g, i) -> trackPreferenceRank(textPreference, g.getTrackFormat(i).language, g.getTrackFormat(i).id) < 2 }
                                        .minByOrNull { (g, i) -> trackPreferenceRank(textPreference, g.getTrackFormat(i).language, g.getTrackFormat(i).id) }
                                    ?: texts.firstOrNull { (g, i) -> dev.lumenara.catalog.isTurkish(g.getTrackFormat(i).language) }
                                if (selectedText == null) params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                else selectedText.let { (g, i) -> params.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) }
                            }
                            engine.trackSelectionParameters = params.build()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            playbackActive = isPlaying
                            if (isPlaying) { retry.playing(); error = "" }
                            if (isPlaying && !preview && !recorded) {
                                recorded = true
                                model.started(item)
                            }
                        }

                        override fun onPlayerError(cause: PlaybackException) {
                            model.diagnostic("Media3 ${cause.errorCode}")
                            when {
                                cause.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                                    engine.seekToDefaultPosition()
                                    engine.prepare()
                                }
                                cause.errorCode in 4000..4999 -> {
                                    retry.playing()
                                    error = playbackMessage(cause)
                                    decoderFailure(engine.currentPosition)
                                }
                                item.kind == "LIVE" && cause.errorCode in 2000..3999 && retry.failed() -> Unit
                                else -> { retry.stop(); error = playbackMessage(cause) }
                            }
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) {
                                model.progress(item, engine.duration, engine.duration)
                                currentEnded()
                            }
                        }
                    }
                listener = mediaListener
                engine.addListener(mediaListener)
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
                    android.util.Log.w("LumenaraPlayback", "${e.javaClass.simpleName} · $frames")
                }
                if (item.kind != "LIVE" || !retry.failed())
                    error = "Yayın adresi açılamadı. Kaynağı yenileyin."
            }
        }
    }
    DisposableEffect(item.id, item.encryptedUrl, attempt, subtitle) {
        onDispose {
            player?.let { engine ->
                resume = if (engine.playbackState == Player.STATE_ENDED && engine.duration > 0)
                    engine.duration else engine.currentPosition
                model.progress(item, resume, engine.duration)
                listener?.let(engine::removeListener)
                listener = null
                engine.stop()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
            currentReady(null)
        }
    }
    LaunchedEffect(player, item.id) {
        val engine = player ?: return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
            wallClockTicks(5000).collect {
                if (engine.isPlaying) model.progress(item, engine.currentPosition, engine.duration)
            }
        }
    }
    Box(modifier.background(androidx.compose.ui.graphics.Color.Black).semantics {
        if (preview) stateDescription = if (playbackActive) "Oynatılıyor" else "Yükleniyor"
        else if (subtitleText.isNotBlank()) stateDescription = "Altyazı · $subtitleText"
    }) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                    videoOutput = this
                }
            },
            update = {
                it.isFocusable = false
                it.isFocusableInTouchMode = false
                it.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                if (it.player !== player) it.player = player
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
        FrameRateMatch(model, item.kind, frameRate, videoOutput, active = !preview && !multi)
        if (!preview) Reconnecting(retry)
        if (error.isNotEmpty()) {
            val retryFocus = remember { FocusRequester() }
            if (!preview) LaunchedEffect(error) { retryFocus.requestFocus() }
            Column(Modifier.align(Alignment.Center).background(Ink.secondary).padding(12.dp)) {
                Text(error, maxLines = 3)
                if (!preview) Button(onClick = { error = ""; retry.manual() },
                    modifier = Modifier.focusRequester(retryFocus)) { Text("Yeniden dene") }
            }
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
    initial: Long = -1,
    sleepTimer: dev.lumenara.catalog.SleepTimer? = null,
    sleepTimerExpired: () -> Unit = back,
) {
    val activeSleepTimer = sleepTimer ?: remember { dev.lumenara.catalog.SleepTimer() }
    var panel by remember { mutableStateOf(false) }
    var multi by remember { mutableStateOf(emptyList<Item>()) }
    var hint by remember { mutableStateOf("") }
    var subtitleNotice by remember(item.id) { mutableStateOf("") }
    var channelQuery by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(80) }
    val slots = remember { decoderSlots() }
    val scope = rememberCoroutineScope()
    var moveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val activeId by rememberUpdatedState(item.id)
    DisposableEffect(item.id) { onDispose { moveJob?.cancel() } }
    var sleepTimerVersion by remember { mutableIntStateOf(0) }
    val currentSleepTimerExpired by rememberUpdatedState(sleepTimerExpired)
    LaunchedEffect(activeSleepTimer) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            sleepTimerVersion++
            if (activeSleepTimer.poll()) currentSleepTimerExpired()
        }
    }
    val sleepCountdown = sleepTimerVersion.let { activeSleepTimer.showCountdown() }
    LaunchedEffect(sleepCountdown) { if (sleepCountdown) { panel = false; multi = emptyList() } }
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
    var failed by remember(item.id) { mutableStateOf(false) }
    var engine by remember { mutableStateOf<ExoPlayer?>(null) }
    val focus = remember { FocusRequester() }
    val composeView = LocalView.current
    val live = item.kind == "LIVE"
    val fullscreen = rememberFullscreenState(item)
    fun episodeEnded() {
        if (activeSleepTimer.episodeEnded()) sleepTimerExpired()
        else if (item.kind == "EPISODE") fullscreen.finished = group.nextItem(item) != null
    }
    var subtitle by remember(item.id) { mutableStateOf<MediaItem.SubtitleConfiguration?>(null) }
    var preferenceKey by remember(item.id) { mutableStateOf("") }
    var delayMs by remember(item.id) { mutableLongStateOf(0) }
    val subtitleDelay = remember { java.util.concurrent.atomic.AtomicLong() }
    LaunchedEffect(item.id) {
        preferenceKey = model.repo.preferenceKey(item)
        delayMs = model.prefs.text("delay_$preferenceKey", "0").first().toLongOrNull() ?: 0
        subtitleDelay.set(delayMs * 1000)
        val cached = withContext(Dispatchers.IO) { model.repo.subtitles.cache.find(preferenceKey) }
        if (cached != null && model.prefs.playbackChoice("text", preferenceKey) != "off") subtitle = subtitleConfiguration(cached)
    }
    fun move(delta: Int) {
        if (item.kind == "LIVE" && group is LiveChannelGroup) {
            moveJob?.cancel()
            moveJob = scope.launch {
                liveNeighbor(model, item, group, delta)?.let { (channel, rows) ->
                    if (activeId == item.id) { Perf.mark("channel"); changeGroup(rows); select(channel) }
                }
            }
            return
        }
        if (item.kind == "EPISODE" && delta == 1) group.nextItem(item)?.let(select)
        else {
            val rows = if (item.kind == "EPISODE") group.seriesOrder() else group
            val at = rows.indexOfFirst { it.id == item.id }
            if (at >= 0) rows.getOrNull(at + delta)?.let(select)
        }
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
    var vlc by remember(item.id) { mutableStateOf(preferred == "VLC") }
    var transferred by remember(item.id) { mutableLongStateOf(initial) }
    var switched by remember(item.id) { mutableStateOf(false) }
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
            group = group,
            subtitle = subtitle,
            attachSubtitle = { subtitle = it },
            select = { channel, rows -> changeGroup(rows); select(channel) },
            next = { if (item.kind == "EPISODE") move(1) },
            sleepTimer = activeSleepTimer,
            sleepTimerVersion = sleepTimerVersion,
            onSleepTimerChanged = { sleepTimerVersion++ },
            episodeEnded = ::episodeEnded,
        )
        return
    }
    val numbers = if (live) rememberLiveNumberControl(model, item.source, item.id) { channel, rows ->
        changeGroup(rows); select(channel)
    } else null
    val retry = rememberLiveRetry(item.id)
    val subtitleSearch = rememberSubtitleSearchState(model, item, status = { subtitleNotice = it }) {
        subtitle = it
        panel = false
    }
    BackHandler { if (panel) panel = false else if (!fullscreen.dismiss()) back() }
    LaunchedEffect(subtitleSearch.open, panel, fullscreen.guide, fullscreen.finished, failed, engine, retry.generation, retry.reconnecting, sleepCountdown) {
        if (!subtitleSearch.open && !panel && !fullscreen.guide && !fullscreen.finished && !failed && !sleepCountdown) {
            // PlayerView updates and reconnect badge removal must finish before reclaiming focus.
            withFrameNanos { }
            if (!composeView.hasFocus()) composeView.requestFocus()
            val granted = focus.requestFocus()
            android.util.Log.d("LumenaraFocus", "Media3 trigger=engine/retry/fullscreen granted=$granted viewHasFocus=${composeView.hasFocus()}")
        }
    }
    Box(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .focusRequester(focus)
            .semantics { contentDescription = "Tam ekran oynatıcı" }
            .onPreviewKeyEvent {
                if (subtitleSearch.open || sleepCountdown) return@onPreviewKeyEvent false
                val key = it.nativeKeyEvent
                val code = key.keyCode
                if (!panel && !failed && fullscreen.key(key, live, ::move,
                        { panel = true },
                        { delta -> engine?.let { p -> p.seekTo((p.currentPosition + delta).coerceAtLeast(0)) } },
                        { event -> numbers?.key(event) == true })) {
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
            failure = { failed = it },
            liveRetry = retry,
            initial = transferred,
            subtitle = subtitle,
            subtitleDelayUs = subtitleDelay,
            decoderFailure = { pos ->
                if (!switched && fallback == "Açık") {
                    switched = true
                    transferred = pos
                    vlc = true
                }
            },
            ended = {
                if (item.kind == "EPISODE") episodeEnded()
            },
        )
        if (!panel && !sleepCountdown) FullscreenOverlay(model, item, group, fullscreen,
            { channel, rows -> changeGroup(rows); select(channel) },
            { panel = true },
            { engine?.currentPosition ?: 0 }, { engine?.duration ?: 0 },
            if (item.kind == "EPISODE" && group.nextItem(item) != null)
                ({ move(1) }) else null)
        numbers?.let { NumberEntry(it) }
        SubtitleNotice(subtitleNotice) { subtitleNotice = "" }
        SleepTimerCard(activeSleepTimer, sleepTimerVersion) {
            activeSleepTimer.cancel()
            sleepTimerVersion++
        }
        val optionsState = rememberLazyListState()
        LaunchedEffect(panel) { if (!panel) optionsState.scrollToItem(0) }
        if (panel && !subtitleSearch.open) {
            val candidates by
                remember(item.source, category, channelQuery, limit) {
                        model.repo.browse(
                            item.source,
                            "LIVE",
                            category,
                            dev.lumenara.catalog.searchable(channelQuery),
                            "",
                            limit,
                        )
                    }
                    .collectAsStateWithLifecycle(emptyList())
            val categories by
                remember(item.source) { model.repo.categories("LIVE", item.source) }
                    .collectAsStateWithLifecycle(emptyList())
            val close = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                if (!subtitleSearch.restore) close.requestFocus()
            }
            val mark by
                remember(item.id) { model.repo.library.observeMark(item.id) }
                    .collectAsStateWithLifecycle(null)
            LazyColumn(
                state = optionsState,
                modifier = Modifier.align(Alignment.CenterEnd).width(400.dp).fillMaxHeight().background(LumenColors.surface1).testTag("player-options").padding(horizontal = Spacing.titleGap, vertical = Spacing.screenVertical)
                    .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                item {
                    Text("Seçenekler", style = LumenType.title)
                    Text(playbackDisplayTitle(item), style = LumenType.cardTitle, maxLines = 2)
                    Text(item.category, style = LumenType.meta)
                }
                item { PlaybackSection("OYNATICI") }
                item { SleepTimerChoices(activeSleepTimer, item.kind == "EPISODE") { sleepTimerVersion++ } }
                item {
                    PlayerOption(onClick = { panel = false }, modifier = Modifier.focusRequester(close)) {
                        Text("Yayına dön")
                    }
                }
                item {
                    PlayerOption(onClick = { model.mark(item, true) }) {
                        Text(if (mark?.favorite == true) "Favoriden çıkar" else "Favoriye ekle")
                    }
                }
                item { VersionChoice(model, item) { version ->
                    scope.launch {
                        // Read ExoPlayer on the main thread; updateMark runs its transform inside a DB transaction.
                        // ExoPlayer must be read on the main thread; this callback may resume elsewhere.
                        val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                            engine?.let { it.currentPosition.coerceAtLeast(0) to it.duration.coerceAtLeast(0) }
                        }
                        if (snapshot != null)
                            model.repo.updateMark(item.id) { it.copy(position = snapshot.first, duration = snapshot.second) }
                        if (version.kind == "EPISODE") changeGroup(model.repo.episodeSnapshot(version.parent).seriesOrder())
                        select(version)
                    }
                } }
                item { Tracks(engine, model, item) }
                item { PlaybackSection("ALTYAZI") }
                item { SubtitleSearch(subtitleSearch) }
                item {
                    Text("Altyazı kaydırma · ${delayMs / 1000.0} sn")
                    Column { for (step in listOf(-500L, 500L)) PlayerOption(onClick = {
                        delayMs = (delayMs + step).coerceIn(-60_000, 60_000)
                        subtitleDelay.set(delayMs * 1000)
                        scope.launch { model.prefs.set("delay_$preferenceKey", delayMs.toString()) }
                    }) { Text(if (step < 0) "−0,5 sn" else "+0,5 sn") } }
                }
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
                        PlayerOption(onClick = { guide = !guide }) { Text("Kanal rehberi") }
                        if (guide)
                            GuidePanel(model, item) { replay ->
                                changeGroup(listOf(replay))
                                select(replay)
                            }
                    }
                item {
                    Subtitles { configuration ->
                        scope.launch {
                            model.prefs.set("text_$preferenceKey", if (configuration == null) "off" else "tr")
                            subtitle = configuration
                            panel = false
                        }
                    }
                }
                item { PlaybackSection("GÖRÜNTÜ") }
                if (!live)
                    item {
                        PlayerOption(
                            onClick = {
                                engine?.let { if (it.isPlaying) it.pause() else it.play() }
                            }
                        ) {
                            Text("Duraklat / devam")
                        }
                        Column {
                            for (step in listOf(-10000, 10000)) PlayerOption(
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
                    PlayerOption(
                        onClick = {
                            transferred = engine?.currentPosition ?: -1
                            android.util.Log.d("LumenaraVlc", "switch Media3 to VLC position=$transferred subtitle=${subtitle != null} scheme=${subtitle?.uri?.scheme}")
                            switched = true
                            panel = false
                            vlc = true
                        }
                    ) {
                        Text("Motor: Media3/VLC")
                    }
                }
                item {
                    if (live)
                        for (count in listOf(2, 4)) PlayerOption(
                            onClick = {
                                scope.launch {
                                    val options = (listOf(item) + group).distinctBy { it.id }
                                    val limits =
                                        options
                                            .map { it.source }
                                            .distinct()
                                            .associateWith { model.prefs.connections(it).first() }
                                    val selection =
                                        dev.lumenara.catalog.multiSelection(
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
                        PlayerOption(
                            onClick = { e.seekTo((e.currentPosition - 30_000).coerceAtLeast(0)) }
                        ) {
                            Text("30 sn geri")
                        }
                        PlayerOption(onClick = { if (e.isPlaying) e.pause() else e.play() }) {
                            Text("Duraklat / devam")
                        }
                        PlayerOption(onClick = { e.seekToDefaultPosition() }) { Text("Canlıya dön") }
                    }
                }
                if (live)
                    item {
                        Field("Kanal ara", channelQuery, { channelQuery = it })
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            items(listOf("") + categories, key = { it }) { name ->
                                PlayerOption(onClick = { category = name }) {
                                    Text(
                                        name.ifEmpty { "Tümü" }
                                    )
                                }
                            }
                        }
                    }
                val list =
                    if (live) candidates else group
                items(list, key = { it.id }) { channel ->
                    PlayerOption(
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
                if (live && candidates.size >= limit)
                    item { PlayerOption(onClick = { limit += 80 }) { Text("Devamını göster") } }
            }
        }
        SubtitleResults(subtitleSearch, panel, focus)
    }
}

@Composable
internal fun SleepTimerChoices(
    timer: dev.lumenara.catalog.SleepTimer,
    episodeAvailable: Boolean,
    changed: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        PlayerOption(onClick = { expanded = !expanded }, modifier = Modifier.focusRequester(focus),
            icon = LumenIcon.Timer) {
            Text("Uyku zamanlayıcısı", Modifier.weight(1f))
            Text(timer.choice.label, style = LumenType.meta)
        }
        if (expanded) {
            BackHandler { expanded = false; focus.requestFocus() }
            val choices = dev.lumenara.catalog.SleepTimer.Choice.values().filter {
                it != dev.lumenara.catalog.SleepTimer.Choice.EPISODE || episodeAvailable
            }
            choices.forEach { choice ->
                PlayerOption(onClick = {
                    timer.select(choice); changed(); expanded = false; focus.requestFocus()
                }, icon = if (timer.choice == choice) LumenIcon.Check else LumenIcon.Timer) {
                    Text(choice.label)
                }
            }
        }
    }
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.SleepTimerCard(
    timer: dev.lumenara.catalog.SleepTimer,
    version: Int,
    cancel: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val refresh = version
    if (!timer.showCountdown()) return
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; cancelFocus.requestFocus() }
    val seconds = ((timer.remainingMs() ?: 0L) + 999) / 1000
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 36.dp).background(LumenColors.surface1, androidx.compose.foundation.shape.RoundedCornerShape(Radii.panel)).padding(Spacing.cardGap),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Lumenara $seconds sn sonra duracak")
        Button(onClick = cancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("İptal (OK)") }
        Button(onClick = { timer.addFifteenMinutes() }) { Text("+15 dk") }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Tracks(player: ExoPlayer?, model: LibraryModel, item: Item) {
    val scope = rememberCoroutineScope()
    var preferenceKey by remember(item.id) { mutableStateOf("") }
    LaunchedEffect(item.id) { preferenceKey = model.repo.preferenceKey(item) }
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
    for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) key(type) {
        val groups = tracks.groups.filter { it.type == type }
        val label = if (type == C.TRACK_TYPE_AUDIO) "Ses" else "Altyazı"
        var expanded by remember { mutableStateOf(false) }
        val entry = remember { FocusRequester() }
        val first = remember { FocusRequester() }
        val selected = groups.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let {
                dev.lumenara.catalog.languageName(group.getTrackFormat(it).language)
            }
        } ?: "Kapalı"
        PlaybackSection(if (type == C.TRACK_TYPE_AUDIO) "SES" else "ALTYAZI")
        PlayerOption(onClick = { expanded = !expanded }, modifier = Modifier.focusRequester(entry),
            icon = if (type == C.TRACK_TYPE_AUDIO) LumenIcon.Audio else LumenIcon.Subtitles) {
            Text(label, Modifier.weight(1f))
            Text(selected, style = LumenType.meta)
        }
        if (expanded) {
            BackHandler { expanded = false; entry.requestFocus() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                if (type == C.TRACK_TYPE_TEXT || groups.any { group ->
                    (0 until group.length).any { group.isTrackSupported(it) }
                }) first.requestFocus()
            }
            var firstTrack = true
            for (group in groups) for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val modifier = if (firstTrack) Modifier.focusRequester(first) else Modifier
                firstTrack = false
                PlayerOption(onClick = {
                    scope.launch { model.prefs.set("${if (type == C.TRACK_TYPE_AUDIO) "audio" else "text"}_$preferenceKey",
                        trackChoice(format.language, format.id)) }
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                    expanded = false
                    entry.requestFocus()
                }, modifier = modifier,
                    icon = if (group.isTrackSelected(i)) LumenIcon.Check else LumenIcon.ChevronRight) {
                    Text("$label · ${dev.lumenara.catalog.languageName(format.language)}" +
                        (format.label?.let { " · $it" } ?: " · ${i + 1}"))
                }
            }
            if (type == C.TRACK_TYPE_TEXT) PlayerOption(onClick = {
                scope.launch { model.prefs.set("text_$preferenceKey", "off") }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, true).build()
                expanded = false
                entry.requestFocus()
            }, modifier = if (firstTrack) Modifier.focusRequester(first) else Modifier) { Text("Altyazıyı kapat") }
            if (type == C.TRACK_TYPE_AUDIO && firstTrack) {
                LaunchedEffect(Unit) { expanded = false; entry.requestFocus() }
            }
        }
    }
}

/** Local player controls keep the design system's list focus treatment. */
@Composable
internal fun PlayerOption(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: LumenIcon = LumenIcon.Settings,
    content: @Composable RowScope.() -> Unit,
) {
    ListRow(onClick = onClick, modifier = modifier, enabled = enabled) {
        LumenIcon(icon, null)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
            ProvideTextStyle(LumenType.label) { content() }
        }
        LumenIcon(LumenIcon.ChevronRight, null, tint = LumenColors.textSecondary)
    }
}

@Composable
internal fun PlaybackSection(title: String) {
    Text(title, Modifier.padding(top = Spacing.cardGap, bottom = Spacing.unit),
        style = LumenType.caption, color = LumenColors.textSecondary)
}

/** Clean only the visible title; production identity and version selection stay intact. */
internal fun playbackDisplayTitle(item: Item): String =
    dev.lumenara.catalog.productionName(item.title, item.year).title.displayTitle().ifBlank { "Video" }
