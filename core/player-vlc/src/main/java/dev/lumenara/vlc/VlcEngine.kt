package dev.lumenara.vlc

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var input: VlcInput? = null
    @Volatile private var operation = "idle"

    /** Cached state only: never query JNI or acquire the playback mutex from a watchdog. */
    fun diagnosticState() = "engine=VLC operation=$operation player=${player != null} " +
        "${input?.diagnosticState() ?: "input=none"}"

    private inline fun <T> trace(step: String, block: () -> T): T {
        val began = SystemClock.elapsedRealtime()
        operation = step
        Log.d("LumenaraVlc", "$step begin tid=${android.os.Process.myTid()} ${diagnosticState()}")
        var returned = false
        try {
            return block().also { returned = true }
        } finally {
            operation = "$step:${if (returned) "returned" else "failed"}"
            Log.d("LumenaraVlc", "$step end returned=$returned ms=${SystemClock.elapsedRealtime() - began} ${diagnosticState()}")
        }
    }

    suspend fun <T> withPlayer(block: suspend VlcEngine.() -> T): T = mutex.withLock { block() }

    /** Called inside withPlayer; cancellation cannot strand a descriptor or partially installed media. */
    suspend fun open(address: String, allowHttp: Boolean, start: Long, live: Boolean): MediaPlayer =
        withContext(Dispatchers.IO) {
            check(input == null)
            val lib = trace("runtime-get") { VlcRuntime.get(context) }
            ensureActive()
            val p = player ?: trace("player-create") { MediaPlayer(lib).also { player = it } }
            input = trace("input-open") { VlcInput.openCancellable(context, address, allowHttp, network) }
            ensureActive()
            val media = Media(lib, checkNotNull(input).descriptor.fileDescriptor)
            try {
                media.addOption(":demux=" + checkNotNull(input).demux)
                if (start > 0 && !live) media.addOption(":start-time=${start / 1000.0}")
                trace("media-set") { p.media = media }
            } finally { media.release() }
            p
        }

    private fun checkThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun play() { checkThread(); trace("play") { player?.play() } }
    override fun pause() { checkThread(); trace("pause") { player?.pause() } }
    override fun seekTo(position: Long) { checkThread(); trace("seek") { player?.setTime(position.coerceAtLeast(0)) } }
    override fun snapshot(): EngineSnapshot {
        checkThread()
        return player?.let { EngineSnapshot(it.time, it.length, it.isPlaying) } ?: EngineSnapshot(0, 0, false)
    }

    /** Inside withPlayer, after detaching the event listener. Never close an fd before native stop. */
    override suspend fun stop(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        dev.lumenara.player.api.wp18CloseInput(
            cancelReads = { trace("input-cancel") { input?.cancel() } },
            stopNative = {
                trace("native-stop") { player?.stop() }
                trace("media-clear") { player?.media = null }
            },
            closeDescriptor = { trace("fd-close") { try { input?.close() } finally { input = null } } },
        )
    }

    /** Serialized after the attempt's stop/fd-close, retaining the surface until then. */
    override suspend fun release(): Unit = withContext(NonCancellable) {
        Log.d("LumenaraVlc", "release waiting for mutex ${diagnosticState()}")
        mutex.withLock {
            Log.d("LumenaraVlc", "release acquired mutex ${diagnosticState()}")
            stop()
            val p = player
            player = null
            withContext(Dispatchers.Main.immediate) {
                p?.setEventListener(null)
                trace("surface-detach") { p?.detachViews() }
            }
            withContext(Dispatchers.IO) { trace("player-release") { p?.release() } }
        }
    }
}
