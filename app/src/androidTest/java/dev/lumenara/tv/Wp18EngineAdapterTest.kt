package dev.lumenara.tv

import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Transport
import dev.lumenara.player.Media3Engine
import dev.lumenara.player.api.PlaybackNetwork
import dev.lumenara.vlc.VlcEngine
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.atomic.AtomicReference

/** Native output assertions, with no codec-dependent skip or node-existence success condition. */
@UnstableApi
class Wp18EngineAdapterTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 90_000) fun tsVlcHlsMedia3AndTsReturnRenderFramesAndAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ts = instrumentation.context.assets.open("mp2-audio-test.ts").use { it.readBytes() }
        val video = instrumentation.context.assets.open("lumenara-test.ts").use { it.readBytes() }
        val mp4 = instrumentation.context.assets.open("audio-subtitle-test.mp4").use { it.readBytes() }
        val audio = tone()
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error -> failure.set(error) })
        lateinit var vlcView: VLCVideoLayout
        lateinit var exoView: PlayerView
        lateinit var vlc: VlcEngine
        lateinit var exo: Media3Engine
        var native: MediaPlayer? = null
        var attachedPlayer: MediaPlayer? = null
        var media3: ExoPlayer? = null
        var audioAdvancing = false
        var attempt: Job? = null
        var released = false
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/stream.ts" -> MockResponse().setBody(Buffer().write(ts)).setHeader("Content-Type", "video/mp2t")
                    "/segment.ts" -> MockResponse().setBody(Buffer().write(video)).setHeader("Content-Type", "video/mp2t")
                    "/tone.wav" -> MockResponse().setBody(Buffer().write(audio)).setHeader("Content-Type", "audio/wav")
                    "/stream.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:30\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:30,\nsegment.ts\n#EXT-X-ENDLIST\n")
                    "/audio.mp4" -> {
                        val range = request.getHeader("Range")?.substringAfter("bytes=")
                        val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
                        val end = (range?.substringAfter('-')?.toIntOrNull() ?: mp4.lastIndex).coerceAtMost(mp4.lastIndex)
                        if (start > end) MockResponse().setResponseCode(416)
                        else MockResponse().setResponseCode(if (range == null) 200 else 206)
                            .setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                            .apply { if (range != null) setHeader("Content-Range", "bytes $start-$end/${mp4.size}") }
                            .setBody(Buffer().write(mp4, start, end - start + 1))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            screen.runOnUiThread {
                vlc = VlcEngine(screen.activity, PlaybackNetwork { Transport.client(it) })
                exo = Media3Engine(screen.activity)
                vlcView = VLCVideoLayout(screen.activity)
                exoView = PlayerView(screen.activity).apply { useController = false; visibility = View.GONE }
                screen.activity.setContent {
                    AndroidView(factory = { context ->
                        FrameLayout(context).apply {
                            addView(vlcView, FrameLayout.LayoutParams(-1, -1))
                            addView(exoView, FrameLayout.LayoutParams(-1, -1))
                        }
                    })
                }
            }
            fun await(condition: () -> Boolean) {
                screen.waitUntil(15_000) {
                    failure.get()?.let { throw AssertionError("Adapter failed", it) }
                    onMain(condition)
                }
            }
            fun startVlc() {
                screen.runOnUiThread {
                    exoView.visibility = View.GONE
                    vlcView.visibility = View.VISIBLE
                    attempt = scope.launch {
                        vlc.withPlayer {
                            try {
                                val p = open(server.url("/stream.ts").toString(), true, 0, true)
                                native = p
                                if (attachedPlayer == null) {
                                    p.attachViews(vlcView, null, true, false)
                                    attachedPlayer = p
                                } else assertSame("Live input changes reuse the native player and surface", attachedPlayer, p)
                                play()
                                awaitCancellation()
                            } finally {
                                native = null
                                stop()
                            }
                        }
                    }
                }
                await {
                    val media = native?.media
                    try {
                        val stats = media?.stats
                        (stats?.displayedPictures ?: 0) > 0 && (stats?.playedAbuffers ?: 0) > 0
                    } finally { media?.release() }
                }
            }
            try {
                startVlc()
                screen.runOnUiThread { attempt?.cancel() }
                await { attempt?.isCompleted == true }
                screen.runOnUiThread {
                    vlcView.visibility = View.GONE
                    exoView.visibility = View.VISIBLE
                    media3 = exo.acquire(true, true, false, "Dengeli", null, null)
                    media3?.addAnalyticsListener(object : AnalyticsListener {
                        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime,
                            playoutStartSystemTimeMs: Long) {
                            audioAdvancing = true
                        }
                    })
                    exoView.player = media3
                    exo.install(MediaItem.fromUri(server.url("/stream.m3u8").toString()), 0)
                    exo.prepare(true)
                }
                await {
                    media3?.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount > 0 } == true
                }
                // Existing HLS/MP4 fixtures are video-only. A bounded PCM tone exercises AudioTrack.
                screen.runOnUiThread {
                    exo.install(MediaItem.fromUri(server.url("/tone.wav").toString()), 0)
                    exo.prepare(true)
                }
                await {
                    audioAdvancing && media3?.audioDecoderCounters?.let {
                        it.ensureUpdated(); it.renderedOutputBufferCount > 0
                    } == true
                }
                screen.runOnUiThread {
                    exo.install(MediaItem.fromUri(server.url("/audio.mp4").toString()), 0)
                    exo.prepare(false)
                    exo.seekTo(5_000)
                }
                await { media3?.currentPosition == 5_000L && media3?.playWhenReady == false &&
                    media3?.playbackState == Player.STATE_READY }
                screen.runOnUiThread { exoView.player = null; exo.releaseNow(); media3 = null }
                startVlc()
            } finally {
                screen.runOnUiThread {
                    attempt?.cancel()
                    scope.launch {
                        try {
                            attempt?.join()
                            vlc.release()
                            exoView.player = null
                            exo.release()
                        } finally { released = true }
                    }
                }
                screen.waitUntil(15_000) { onMain { released } }
                screen.runOnUiThread { screen.activity.setContent { } }
                scope.cancel()
            }
        }
        failure.get()?.let { throw AssertionError("Adapter failed", it) }
    }

    private fun tone(): ByteArray {
        val samples = 48_000 * 10
        val buffer = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun tag(value: String) { buffer.put(value.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); buffer.putInt(36 + samples * 2); tag("WAVE"); tag("fmt ")
        buffer.putInt(16); buffer.putShort(1); buffer.putShort(1)
        buffer.putInt(48_000); buffer.putInt(96_000); buffer.putShort(2); buffer.putShort(16)
        tag("data"); buffer.putInt(samples * 2)
        repeat(samples) { index ->
            buffer.putShort((kotlin.math.sin(2 * Math.PI * 440 * index / 48_000) * 2_000).toInt().toShort())
        }
        return buffer.array()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
