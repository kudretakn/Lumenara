package dev.lumenara.vlc

import android.content.Context
import android.os.Looper
import dev.lumenara.player.api.EngineKind
import dev.lumenara.player.api.EngineSnapshot
import dev.lumenara.player.api.PlaybackEngine
import dev.lumenara.player.api.PlaybackNetwork
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/** Owns native creation and input teardown. The app serializes attempts with [withPlayer]. */
class VlcEngine(private val context: Context, private val network: PlaybackNetwork) : PlaybackEngine {
    override val kind = EngineKind.VLC
    private val mutex = Mutex()
    private var player: MediaPlayer? = null
    private var input: VlcInput? = null

    suspend fun <T> withPlayer(block: suspend VlcEngine.() -> T): T = mutex.withLock { block() }

    /** Called inside withPlayer; cancellation cannot strand a descriptor or partially installed media. */
    suspend fun open(address: String, allowHttp: Boolean, start: Long, live: Boolean): MediaPlayer =
        withContext(Dispatchers.IO) {
            check(input == null)
            val lib = VlcRuntime.get(context)
            ensureActive()
            val p = player ?: MediaPlayer(lib).also { player = it }
            input = VlcInput.openCancellable(context, address, allowHttp, network)
            ensureActive()
            val media = Media(lib, checkNotNull(input).descriptor.fileDescriptor)
            try {
                media.addOption(":demux=" + checkNotNull(input).demux)
                if (start > 0 && !live) media.addOption(":start-time=${start / 1000.0}")
                p.media = media
            } finally { media.release() }
            p
        }

    private fun checkThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun play() { checkThread(); player?.play() }
    override fun pause() { checkThread(); player?.pause() }
    override fun seekTo(position: Long) { checkThread(); player?.setTime(position.coerceAtLeast(0)) }
    override fun snapshot(): EngineSnapshot {
        checkThread()
        return player?.let { EngineSnapshot(it.time, it.length, it.isPlaying) } ?: EngineSnapshot(0, 0, false)
    }

    /** Inside withPlayer, after detaching the event listener. Never close an fd before native stop. */
    override suspend fun stop(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        dev.lumenara.player.api.wp18CloseInput(
            cancelReads = { input?.cancel() },
            stopNative = { player?.stop(); player?.media = null },
            closeDescriptor = { try { input?.close() } finally { input = null } },
        )
    }

    /** Serialized after the attempt's stop/fd-close, retaining the surface until then. */
    override suspend fun release(): Unit = withContext(NonCancellable) {
        mutex.withLock {
            stop()
            val p = player
            player = null
            withContext(Dispatchers.Main.immediate) { p?.setEventListener(null); p?.detachViews() }
            withContext(Dispatchers.IO) { p?.release() }
        }
    }
}
