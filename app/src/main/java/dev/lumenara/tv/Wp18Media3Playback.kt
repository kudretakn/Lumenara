package dev.lumenara.tv

import android.content.Context
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import dev.lumenara.catalog.trackPreferenceRank
import dev.lumenara.data.Item
import dev.lumenara.player.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-thread controller: Compose observes values and binds a PlayerView only. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Wp18Media3Playback(
    context: Context,
    private val model: LibraryModel,
    val shiftedSubtitles: SubtitleShiftOutput,
) {
    private val adapter = Media3Engine(context)
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var playbackActive by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
    var subtitleText by mutableStateOf("")
        private set
    var frameRate by mutableStateOf<Float?>(null)
        private set
    var appliedSubtitleDelayUs by mutableLongStateOf(0)
        private set
    var currentReady: (ExoPlayer?) -> Unit = {}
    var currentEnded: () -> Unit = {}
    var currentLivePlaying: () -> Unit = {}
    var videoFormat: (Wp18VideoLoad) -> Unit = {}
    private var activeBinding: PlaybackOwnership.Binding? = null
    private var dispose: (() -> Unit)? = null

    private fun finish() {
        dispose?.invoke()
        dispose = null
        activeBinding = null
    }
    fun close() {
        requireMainThread()
        finish()
        adapter.releaseNow()
        player = null
        currentReady(null)
    }
    suspend fun run(item: Item, session: PlaybackOwnership, binding: PlaybackOwnership.Binding,
        preview: Boolean, multi: Boolean, retry: LiveRetry, subtitle: MediaItem.SubtitleConfiguration?,
        liveUrls: LiveUrlCache?, decoderFailure: (Long) -> Unit,
        awaitPreviousInput: suspend () -> Unit) = kotlinx.coroutines.coroutineScope {
        session.configureProgress { model.progress(item, it.position, it.duration) }
        finish()
        activeBinding = binding
        val disposePlayback = arrayOfNulls<() -> Unit>(1)
        dispose = { disposePlayback[0]?.invoke(); disposePlayback[0] = null }
        try {
            val perfRequest = if (item.kind == "LIVE" && !preview && !multi)
                Perf.transitions.zapRequest(item.id) else null
            val playbackScope = this
            withContext(Dispatchers.Main.immediate) {
                frameRate = null
                error = ""
                playbackActive = false
                subtitleText = ""
                var monitoredEngine: ExoPlayer? = null
                var reconnectRequested = false
                var terminalError = false
                try {
                    wp09WatchPlayback(
                        monitorStalls = item.kind == "LIVE" && !preview && !multi,
                        snapshot = {
                            val engine = monitoredEngine
                            Wp09PlaybackSample(engine?.currentPosition,
                                paused = !session.wantsPlay || reconnectRequested || terminalError ||
                                    engine?.let { !it.playWhenReady || it.playbackSuppressionReason !=
                                        Player.PLAYBACK_SUPPRESSION_REASON_NONE } == true,
                                buffering = engine?.playbackState == Player.STATE_BUFFERING)
                        },
                        onPrepared = { if (!reconnectRequested) { retry.playing(); error = "" } },
                    ) { watchdog ->
                        awaitPreviousInput()
                        session.awaitInputRelease()
                        val source = model.repo.library.source(item.source) ?: error("Kaynak kaldırıldı")
                        val url = if (item.kind == "LIVE" && liveUrls != null) liveUrls.address(item) else model.repo.address(item)
                        val saved = model.repo.library.mark(item.id)
                        val start = session.startPosition(
                                saved?.let {
                                    if (item.kind == "EPISODE") dev.lumenara.catalog.resumePosition(it.position, it.duration)
                                    else it.position.takeIf { position -> it.duration <= 0 || position < it.duration * .95 } ?: 0
                                } ?: 0)
                        val profile = model.prefs.text("buffer", "Dengeli").first()
                        val engine = adapter.acquire(source.allowHttp, item.kind == "LIVE", multi, profile,
                            subtitle, shiftedSubtitles.takeUnless { preview || multi })
                        player = engine
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
                        val inputId = java.util.UUID.randomUUID().toString()
                        fun ownsInput() = session.owns(binding, engine.currentMediaItem?.mediaId)
                        var recorded = false
                        var formats = ""
                        fun reconnect() {
                            if (reconnectRequested) return
                            reconnectRequested = true
                            if (!retry.failed()) error = "Canlı yayın kesildi. Yeniden deneyin."
                        }
                        val mediaListener = object : Player.Listener {
                                override fun onRenderedFirstFrame() {
                                    if (!ownsInput()) return
                                    watchdog.firstFrame()
                                    if (!preview && !multi) {
                                        Perf.since("channel", "channel_first_frame_media3")
                                    }
                                }
                                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                                    if (!ownsInput()) return
                                    subtitleText = cueGroup.cues.mapNotNull { it.text }.joinToString(" ")
                                }

                                override fun onTracksChanged(tracks: Tracks) {
                                    if (!ownsInput()) return
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
                                    if (!ownsInput()) return
                                    playbackActive = isPlaying
                                    if (isPlaying && item.kind == "LIVE") currentLivePlaying()
                                    if (isPlaying && watchdog.prepared && !reconnectRequested) { retry.playing(); error = "" }
                                    if (isPlaying && !preview && !recorded) {
                                        recorded = true
                                        model.started(item)
                                    }
                                }

                                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                                    if (!ownsInput()) return
                                    session.wantsPlay = playWhenReady
                                }

                                override fun onPlayerError(cause: PlaybackException) {
                                    if (!ownsInput()) return
                                    if (reconnectRequested) return
                                    model.diagnostic("Media3 ${cause.errorCode}")
                                    terminalError = cause.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                                        !(item.kind == "LIVE" && cause.errorCode in 2000..3999)
                                    when {
                                        cause.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                                            engine.seekToDefaultPosition()
                                            adapter.prepare(session.wantsPlay)
                                        }
                                        cause.errorCode in 4000..4999 -> {
                                            retry.playing()
                                            error = playbackMessage(cause)
                                            decoderFailure(engine.currentPosition)
                                        }
                                        item.kind == "LIVE" && cause.errorCode in 2000..3999 -> reconnect()
                                        else -> { retry.stop(); error = playbackMessage(cause) }
                                    }
                                }

                                override fun onPlaybackStateChanged(state: Int) {
                                    if (!ownsInput()) return
                                    if (state == Player.STATE_ENDED) {
                                        if (liveShouldReconnect(item.kind, LivePlaybackSignal.END_REACHED,
                                                paused = !engine.playWhenReady, preview = preview, multi = multi)) reconnect()
                                        if (item.kind != "LIVE") {
                                            session.save(binding, engine.currentMediaItem?.mediaId, engine.duration, engine.duration)
                                            currentEnded()
                                        }
                                    }
                                }
                            }
                        var analytics: AnalyticsListener? = null
                        var metadata: VideoFrameMetadataListener? = null
                        disposePlayback[0] = {
                            if (ownsInput()) {
                                val position = if (engine.playbackState == Player.STATE_ENDED && engine.duration > 0)
                                    engine.duration else engine.currentPosition
                                session.save(binding, engine.currentMediaItem?.mediaId, position, engine.duration)
                                session.wantsPlay = engine.playWhenReady
                                session.unbind(binding)
                            }
                            engine.removeListener(mediaListener)
                            analytics?.let(engine::removeAnalyticsListener)
                            metadata?.let(engine::clearVideoFrameMetadataListener)
                            // Preserve live renderer reuse. An unbound attempt never stops the old input.
                            if (engine.currentMediaItem?.mediaId == inputId && item.kind != "LIVE") adapter.stopNow()
                        }
                        engine.addListener(mediaListener)
                        run {
                            // Renderer/surface reuse need not produce another Player first-frame event.
                            // Arm only when this input reaches the renderer, rejecting buffered old frames.
                            val selectedFormat = Wp18InputFormat(inputId)
                            val inputFormat = java.util.concurrent.atomic.AtomicReference<Format?>(null)
                            val submitted = java.util.concurrent.atomic.AtomicBoolean(false)
                            val frameAnalytics = object : AnalyticsListener {
                                override fun onVideoInputFormatChanged(
                                    eventTime: AnalyticsListener.EventTime,
                                    format: Format,
                                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                                ) {
                                    val timeline = eventTime.timeline
                                    val actualId = if (timeline.isEmpty) null else timeline.getWindow(
                                        eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
                                    if (ownsInput() && selectedFormat.accept(actualId, format.frameRate)) {
                                        inputFormat.set(format)
                                        frameRate = selectedFormat.frameRate
                                        videoFormat(Wp18VideoLoad(format.sampleMimeType, format.width, format.height, format.frameRate))
                                    }
                                }
                            }
                            val frameMetadata = VideoFrameMetadataListener { _, releaseTimeNs, format, _ ->
                                if (perfRequest != null && format === inputFormat.get() && submitted.compareAndSet(false, true)) {
                                    playbackScope.launch(Dispatchers.Main.immediate) {
                                        // The callback precedes release to the surface; never log early.
                                        kotlinx.coroutines.delay(((releaseTimeNs - System.nanoTime()) / 1_000_000)
                                            .coerceAtLeast(0))
                                        if (ownsInput()) Perf.transitions.zapFirstFrame(perfRequest, "media3")
                                    }
                                }
                            }
                            analytics = frameAnalytics
                            engine.addAnalyticsListener(frameAnalytics)
                            if (perfRequest != null) {
                                metadata = frameMetadata
                                engine.setVideoFrameMetadataListener(frameMetadata)
                            }
                        }
                        val media = MediaItem.Builder().setUri(url).setMediaId(inputId)
                        if (subtitle != null) media.setSubtitleConfigurations(listOf(subtitle))
                        adapter.install(media.build(), if (item.kind == "LIVE") 0 else start)
                        session.bind(binding, inputId, if (item.kind == "LIVE") 0 else start)
                        monitoredEngine = engine
                        currentReady(engine)
                        adapter.prepare(session.wantsPlay)
                        launch {
                            wallClockTicks(5000).collect {
                                if (engine.isPlaying) session.save(binding, engine.currentMediaItem?.mediaId,
                                    engine.currentPosition, engine.duration)
                            }
                        }
                        kotlinx.coroutines.awaitCancellation()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is Wp09PlaybackTimeoutException) {
                        monitoredEngine?.takeIf { session.owns(binding, it.currentMediaItem?.mediaId) }?.let { adapter.stopNow() }
                    }
                    disposePlayback[0]?.invoke()
                    disposePlayback[0] = null
                    if (BuildConfig.DEBUG) {
                        val frames =
                            e.stackTrace.take(8).joinToString(" / ") {
                                "${it.className}.${it.methodName}:${it.lineNumber}"
                            }
                        android.util.Log.w("LumenaraPlayback", "${e.javaClass.simpleName} · $frames")
                    }
                    if (item.kind != "LIVE" || !retry.failed())
                        error = if (e is Wp09PlaybackTimeoutException) "Yayın yanıt vermedi. Yeniden deneyin."
                            else "Yayın adresi açılamadı. Kaynağı yenileyin."
                }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                if (activeBinding === binding) finish()
            }
        }
    }
    suspend fun observeSubtitles(binding: PlaybackOwnership.Binding, session: PlaybackOwnership,
        preview: Boolean, multi: Boolean) {
        val engine = player ?: return
        if (preview || multi) return
        withContext(Dispatchers.Main.immediate) {
            val period = androidx.media3.common.Timeline.Period()
            var revision = 0L
            fun clockState(): Wp14SubtitleClockState {
                val owned = session.owns(binding, engine.currentMediaItem?.mediaId)
                return Wp14SubtitleClockState(owned && engine.currentTracks.groups.any {
                    it.type == C.TRACK_TYPE_TEXT && it.isSelected
                }, owned && engine.isPlaying, revision++)
            }
            val clockEvents = kotlinx.coroutines.flow.MutableStateFlow(clockState())
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (session.owns(binding, engine.currentMediaItem?.mediaId))
                        clockEvents.value = clockState()
                }
            }
            engine.addListener(listener)
            try {
                val changes = kotlinx.coroutines.flow.combine(clockEvents, shiftedSubtitles.changes) { state, revision ->
                    state.copy(revision = state.revision + revision)
                }
                wp14SubtitleClock(changes) {
                    requireMainThread()
                    val windowOffsetUs = if (engine.currentTimeline.isEmpty) 0L else
                        engine.currentTimeline.getPeriod(engine.currentPeriodIndex, period).positionInWindowUs
                    shiftedSubtitles.render(engine.currentPosition * 1000 - windowOffsetUs)
                    appliedSubtitleDelayUs = shiftedSubtitles.appliedDelayUs
                }
            } finally {
                engine.removeListener(listener)
            }
        }
    }
}
