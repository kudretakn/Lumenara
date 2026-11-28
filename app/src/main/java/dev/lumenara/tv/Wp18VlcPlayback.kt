package dev.lumenara.tv

import android.content.Context
import androidx.compose.runtime.*
import dev.lumenara.catalog.*
import dev.lumenara.data.Item
import dev.lumenara.data.Transport
import dev.lumenara.player.api.PlaybackNetwork
import dev.lumenara.vlc.VlcEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Owns an attempt from cancellable preparation through native stop and descriptor close. */
internal class Wp18VlcPlayback(private val context: Context, private val model: LibraryModel) {
    private val adapter = VlcEngine(context, PlaybackNetwork { Transport.client(it) })
    var player by mutableStateOf<MediaPlayer?>(null)
        private set
    var activeMediaId by mutableStateOf<String?>(null)
        private set
    var nativePlaying by mutableStateOf(false)
        private set
    var inputHasPlayed by mutableStateOf(false)
        private set
    var mediaGeneration by mutableIntStateOf(0)
        private set
    var trackRevision by mutableLongStateOf(0)
        private set
    var playbackFocus by mutableStateOf<PlaybackAudioFocus?>(null)
        private set
    var message by mutableStateOf("")
    var currentItemId: () -> String = { "" }
    var currentUiVisible: () -> Boolean = { false }
    var currentLivePlaying: () -> Unit = {}
    var showPanel: () -> Unit = {}
    var markFinished: () -> Unit = {}
    var episodeEnded: () -> Unit = {}
    var refreshCurrentSlave: (MediaPlayer) -> Unit = {}
    var refreshCurrentUi: (MediaPlayer) -> Unit = {}

    fun suspendOutput(surface: VLCVideoLayout) {
        requireMainThread()
        surface.keepScreenOn = false
        playbackFocus?.close()
        playbackFocus = null
    }
    suspend fun release() {
        adapter.release()
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            player = null
            activeMediaId = null
        }
    }
    suspend fun run(item: Item, session: PlaybackOwnership, retry: LiveRetry, layout: VLCVideoLayout,
        liveUrls: LiveUrlCache, unsupportedLive: (() -> Unit)?) = coroutineScope {
        session.configureProgress { model.progress(item, it.position, it.duration) }
        val perfRequest = if (item.kind == "LIVE") Perf.transitions.zapRequest(item.id) else null
        val surface = layout
        inputHasPlayed = false
        nativePlaying = false
        activeMediaId = null
        message = ""
        val playbackScope = this
        android.util.Log.d("LumenaraVlc", "playback waiting for mutex retry=${retry.generation}")
        adapter.withPlayer {
            android.util.Log.d("LumenaraVlc", "playback acquired mutex")
            session.awaitInputRelease()
            val inputRelease = session.holdInput()
            var engine: MediaPlayer? = null
            val binding = session.newBinding()
            val bindingId = "vlc-${binding.generation}"
            var bound = false
            var attemptFocus: PlaybackAudioFocus? = null
            var completed = false
            var recorded = false
            var ownStop = false
            var paused = false
            var reconnectRequested = false
            var buffering = false
            var terminalError = false
            fun reconnect() {
                if (reconnectRequested || ownStop) return
                reconnectRequested = true
                if (!retry.failed()) {
                    message = "VLC bu yayını oynatamadı."
                    showPanel()
                }
            }
            try {
                wp09WatchPlayback(
                    monitorStalls = item.kind == "LIVE",
                    snapshot = {
                        Wp09PlaybackSample(engine?.takeIf { bound }?.time,
                            paused = paused || !session.wantsPlay || reconnectRequested || terminalError,
                            buffering = buffering)
                    },
                    onPrepared = { if (!reconnectRequested) { retry.playing(); message = "" } },
                ) { watchdog ->
                    val source = model.repo.library.source(item.source) ?: error("Kaynak kaldırıldı")
                    val address = if (item.kind == "LIVE") liveUrls.address(item) else model.repo.address(item)
                    val mark = model.repo.library.mark(item.id)
                    val start = session.startPosition(mark?.let {
                            if (item.kind == "EPISODE") dev.lumenara.catalog.resumePosition(it.position, it.duration)
                            else it.position.takeIf { position -> it.duration <= 0 || position < it.duration * .95 } ?: 0
                        } ?: 0)
                    engine = adapter.open(address, source.allowHttp, start, item.kind == "LIVE")
                    val p = checkNotNull(engine)
                    withContext(Dispatchers.Main.immediate) {
                        session.bind(binding, bindingId, if (item.kind == "LIVE") 0 else start, dev.lumenara.player.api.EngineKind.VLC)
                        bound = true
                        val focusOwner = PlaybackAudioFocus.create(context, playback = { play ->
                            paused = !play
                            if (!play) {
                                nativePlaying = false
                                surface.keepScreenOn = false
                            }
                            if (!p.isReleased) {
                                // libVLC 3.7.7 pause() calls set_pause(1), including while buffering.
                                if (play && !ownStop) adapter.play() else adapter.pause()
                            }
                        }, intentChanged = { session.wantsPlay = it })
                        attemptFocus = focusOwner
                        playbackFocus = focusOwner
                        // Listener registration creates a Handler; UI writes stay on Main.
                        // Install it before attaching views, which can start playback.
                        p.setEventListener { event ->
                            playbackScope.launch(Dispatchers.Main.immediate) {
                                if (ownStop || p !== player || currentItemId() != item.id ||
                                    !session.owns(binding, bindingId)) return@launch
                                when (event.type) {
                                    MediaPlayer.Event.Opening -> {
                                        nativePlaying = false
                                        mediaGeneration++
                                        trackRevision++
                                        android.util.Log.d("LumenaraVlc", "playback Opening generation=$mediaGeneration")
                                    }
                                    MediaPlayer.Event.Buffering -> {
                                        buffering = event.buffering < 100f
                                    }
                                    MediaPlayer.Event.Playing -> {
                                        // A late surface callback must obey focus and the user's pause intent.
                                        if (!focusOwner.canPlay) {
                                            adapter.pause()
                                            nativePlaying = false
                                            surface.keepScreenOn = false
                                            return@launch
                                        }
                                        paused = false
                                        buffering = false
                                        nativePlaying = true
                                        inputHasPlayed = true
                                        trackRevision++
                                        surface.keepScreenOn = true
                                        android.util.Log.d("LumenaraVlc", "playback Playing generation=$mediaGeneration")
                                        if (item.kind == "LIVE") currentLivePlaying()
                                        if (watchdog.prepared && !reconnectRequested) { retry.playing(); message = "" }
                                        if (!recorded) {
                                            recorded = true
                                            model.started(item)
                                        }
                                    }
                                    MediaPlayer.Event.Paused -> {
                                        paused = true
                                        nativePlaying = false
                                    }
                                    MediaPlayer.Event.Vout -> {
                                        if (event.voutCount > 0) {
                                            Perf.since("channel", "channel_video_output_ready_vlc")
                                        }
                                        refreshCurrentSlave(p)
                                        trackRevision++
                                    }
                                    MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                                        refreshCurrentSlave(p)
                                        trackRevision++
                                    }
                                    MediaPlayer.Event.EncounteredError -> {
                                        nativePlaying = false
                                        focusOwner.stopped(item.kind == "LIVE")
                                        if (item.kind == "LIVE") reconnect()
                                        else {
                                            terminalError = true
                                            message = "VLC bu yayını oynatamadı."
                                            showPanel()
                                        }
                                    }
                                    MediaPlayer.Event.EndReached -> {
                                        nativePlaying = false
                                        focusOwner.stopped(item.kind == "LIVE")
                                        if (liveShouldReconnect(item.kind, LivePlaybackSignal.END_REACHED,
                                                ownStop = ownStop, paused = paused)) reconnect()
                                        if (item.kind != "LIVE") {
                                            completed = true
                                            session.save(binding, bindingId, p.length, p.length)
                                        }
                                        if (item.kind == "EPISODE") {
                                            markFinished()
                                            episodeEnded()
                                        }
                                    }
                                    MediaPlayer.Event.Stopped -> {
                                        nativePlaying = false
                                        focusOwner.stopped(item.kind == "LIVE")
                                        android.util.Log.d("LumenaraVlc", "playback Stopped generation=$mediaGeneration")
                                        if (liveShouldReconnect(item.kind, LivePlaybackSignal.STOPPED,
                                                ownStop = ownStop, paused = paused)) reconnect()
                                    }
                                }
                                surface.keepScreenOn = nativePlaying && focusOwner.canPlay
                                if (currentUiVisible()) refreshCurrentUi(p)
                            }
                        }
                        val needsSurface = player !== p
                        activeMediaId = item.id
                        mediaGeneration++
                        player = p
                        if (needsSurface) p.attachViews(surface, null, true, false)
                        // libVLC may also call play() from its surface-created callback.
                        // Serialize that callback and the initial request on Main.
                        if (session.wantsPlay) focusOwner.play() else focusOwner.pause()
                        android.util.Log.d("LumenaraVlc", "playback play requested generation=$mediaGeneration")
                    }
                    // Vout only means output creation. Stats are explicitly enabled by VlcRuntime.
                    // Read native stats off Main, with a fixed bound and attempt-owned cancellation.
                    val measureFrame = Perf.transitions.zapPending(perfRequest)
                    launch(Dispatchers.IO) {
                        val media = p.media
                        try {
                            repeat(600) {
                                ensureActive()
                                if (!measureFrame && watchdog.prepared) return@launch
                                if ((media?.stats?.displayedPictures ?: 0) > 0) {
                                    withContext(Dispatchers.Main.immediate) {
                                        if (session.owns(binding, bindingId)) {
                                            watchdog.firstFrame()
                                            Perf.transitions.zapFirstFrame(perfRequest, "vlc")
                                        }
                                    }
                                    return@launch
                                }
                                delay(50)
                            }
                        } finally { media?.release() }
                    }
                    var lastSavedAtMs = 0L
                    wallClockTicks(if (item.kind == "LIVE") 500 else 5000).collect {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val playing = p.isPlaying
                        if (playing && now - lastSavedAtMs >= 5000) {
                            lastSavedAtMs = now
                            session.save(binding, bindingId, p.time, p.length)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LumenaraVlc", "VLC playback failed: ${e.javaClass.simpleName}")
                if (item.kind == "LIVE" && e is dev.lumenara.vlc.UnsupportedVlcInputException && unsupportedLive != null) {
                    // A sticky audio fallback must still allow HLS/DASH channels to use Media3.
                    retry.stop()
                    unsupportedLive()
                } else if (item.kind != "LIVE" || e !is java.io.IOException || !retry.failed()) {
                    message = if (e is Wp09PlaybackTimeoutException) "Yayın yanıt vermedi. Yeniden deneyin."
                        else "VLC bu kaynakta TS, MKV veya konumlandırılabilir MP4 dosyası gerektirir."
                    showPanel()
                }
            } finally {
                // Ignore late native events before closing the input or stopping/releasing it.
                ownStop = true
                withContext(NonCancellable) {
                    val p = engine
                    withContext(Dispatchers.Main.immediate) {
                        android.util.Log.d("LumenaraVlc", "playback cleanup current=${player === p} generation=$mediaGeneration")
                        if (player === p) nativePlaying = false
                        surface.keepScreenOn = false
                        attemptFocus?.close()
                        if (playbackFocus === attemptFocus) playbackFocus = null
                        p?.setEventListener(null)
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            p?.let {
                                val lastPosition = it.time
                                val length = it.length
                                withContext(Dispatchers.Main.immediate) {
                                    // A focus-delayed/paused input may still report native time 0.
                                    // Preserve the session start until it has actually played.
                                    if (bound && recorded) session.save(binding, bindingId,
                                        if (completed && length > 0) length else lastPosition, length)
                                    session.unbind(binding)
                                }
                            }
                        } finally {
                            // stop() joins the old native input before its descriptor closes.
                            // Keep the player and attached output for the next channel/retry.
                            try { adapter.stop() } finally {
                                withContext(Dispatchers.Main.immediate) { session.releaseInput(inputRelease) }
                            }
                        }
                    }
                }
            }
        }
    }
}
