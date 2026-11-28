package dev.lumenara.player

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.lumenara.player.api.EngineKind
import dev.lumenara.player.api.EngineSnapshot
import dev.lumenara.player.api.PlaybackEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Retains renderers across live zaps. Every Media3 command stays on its application looper. */
@OptIn(UnstableApi::class)
class Media3Engine(private val context: Context) : PlaybackEngine {
    override val kind = EngineKind.MEDIA3
    private var player: ExoPlayer? = null
    private var configuration: Triple<Boolean, Boolean, String>? = null
    private val allowedHttp = AtomicBoolean(false)
    private val allowedSubtitle = AtomicReference<android.net.Uri?>(null)

    private fun checkThread() {
        check(Looper.myLooper() == (player?.applicationLooper ?: Looper.getMainLooper()))
    }

    fun acquire(allowHttp: Boolean, live: Boolean, multi: Boolean, profile: String,
        subtitle: MediaItem.SubtitleConfiguration?, shiftedSubtitles: SubtitleShiftOutput?): ExoPlayer {
        checkThread()
        val config = Triple(live, multi, profile)
        if (configuration != config) releaseNow()
        allowedHttp.set(allowHttp)
        allowedSubtitle.set(subtitle?.uri)
        val result = player ?: createPlayer(context, allowHttp, !multi, profile, subtitle?.uri,
            automaticRetries = !live, subtitleShiftOutput = shiftedSubtitles, live = live, multi = multi,
            httpAllowed = allowedHttp::get, permittedSubtitle = allowedSubtitle::get).also { player = it }
        configuration = config
        result.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        return result
    }

    fun install(media: MediaItem, start: Long) {
        checkThread()
        checkNotNull(player).setMediaItem(media, start)
    }
    fun prepare(wantsPlay: Boolean) {
        checkThread()
        checkNotNull(player).apply { prepare(); playWhenReady = wantsPlay }
    }
    override fun play() { checkThread(); player?.play() }
    override fun pause() { checkThread(); player?.pause() }
    override fun seekTo(position: Long) { checkThread(); player?.seekTo(position.coerceAtLeast(0)) }
    override fun snapshot(): EngineSnapshot {
        checkThread()
        return player?.let { EngineSnapshot(it.currentPosition, it.duration, it.isPlaying) }
            ?: EngineSnapshot(0, 0, false)
    }
    fun stopNow() { checkThread(); player?.stop() }
    fun releaseNow() {
        checkThread()
        player?.release()
        player = null
        configuration = null
    }
    override suspend fun stop() = withContext(Dispatchers.Main.immediate) { stopNow() }
    override suspend fun release() = withContext(Dispatchers.Main.immediate) { releaseNow() }
}
