package dev.lumenara.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

@OptIn(UnstableApi::class)
fun createPlayer(
    context: Context,
    allowHttp: Boolean,
    audioFocus: Boolean = true,
    profile: String = "Dengeli",
    subtitleUri: android.net.Uri? = null,
    automaticRetries: Boolean = true,
    subtitleDelayUs: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(),
    live: Boolean = false,
    httpAllowed: () -> Boolean = { allowHttp },
    permittedSubtitle: () -> android.net.Uri? = { subtitleUri },
    multi: Boolean = false,
): ExoPlayer {
    val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val largeHeap = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP != 0
    val memoryClassMb = if (largeHeap) memory.largeMemoryClass else memory.memoryClass
    val policy = bufferPolicy(profile, live, multi, memoryClassMb, memory.isLowRamDevice)
    val control = AdaptiveLoadControl(policy.buffers, memoryClassMb, memory.isLowRamDevice, live, multi)
    val requests =
        androidx.media3.datasource.ResolvingDataSource.Factory(
            DataSource.Factory {
                DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(8_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(httpAllowed()))
                    .createDataSource()
            }
        ) { spec ->
            if (
                spec.uri.scheme !in listOf("http", "https") &&
                    !(spec.uri.scheme in listOf("content", "file") && spec.uri == permittedSubtitle())
            ) {
                throw java.io.IOException("Yayın konumu desteklenmiyor.")
            }
            if (spec.uri.scheme == "http" && !httpAllowed()) throw java.io.IOException("HTTP kapalı.")
            spec
        }
    val defaultRenderers = androidx.media3.exoplayer.DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
    val renderers = androidx.media3.exoplayer.RenderersFactory { handler, video, audio, text, metadata ->
        defaultRenderers.createRenderers(handler, video, audio, text, metadata).map { renderer ->
            if (renderer.trackType == androidx.media3.common.C.TRACK_TYPE_TEXT)
                object : androidx.media3.exoplayer.ForwardingRenderer(renderer) {
                    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
                        super.getDurationToProgressUs(positionUs - subtitleDelayUs.get(), elapsedRealtimeUs)

                    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                        super.render(positionUs - subtitleDelayUs.get(), elapsedRealtimeUs)
                    }
                }
            else renderer
        }.toTypedArray()
    }
    return ExoPlayer.Builder(context, renderers)
        .setLoadControl(control)
        .setMediaSourceFactory(DefaultMediaSourceFactory(requests).apply {
            if (!automaticRetries) setLoadErrorHandlingPolicy(
                object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(0) {
                    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                        androidx.media3.common.C.TIME_UNSET
                }
            )
        })
        .build()
        .apply { setAudioAttributes(AudioAttributes.DEFAULT, audioFocus) }
}

fun playbackMessage(error: PlaybackException) =
    when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "Sağlayıcı yayına erişimi reddetti. Listeyi veya aboneliğinizi kontrol edin."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Bağlantı zaman aşımına uğradı."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            "Yayına bağlanılamadı. Ağ bağlantısını kontrol edin."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "Cihaz bu yayının görüntü veya ses biçimini çözemedi."
        else -> "Yayın açılamadı (kod ${error.errorCode})."
    }
