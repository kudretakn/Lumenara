package dev.lumenara.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.lumenara.data.Transport

@OptIn(UnstableApi::class)
fun createPlayer(
    context: Context,
    allowHttp: Boolean,
    audioFocus: Boolean = true,
    profile: String = "Dengeli",
    subtitleUri: android.net.Uri? = null,
    automaticRetries: Boolean = true,
    subtitleDelayUs: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(),
): ExoPlayer {
    val control = DefaultLoadControl.Builder()
    when (profile) {
        "Hızlı" -> control.setBufferDurationsMs(8000, 20000, 750, 1500)
        "Kararlı" -> control.setBufferDurationsMs(30000, 60000, 3000, 5000)
    }
    val requests =
        androidx.media3.datasource.ResolvingDataSource.Factory(
            DefaultDataSource.Factory(
                context,
                OkHttpDataSource.Factory(Transport.client(allowHttp)),
            )
        ) { spec ->
            if (
                spec.uri.scheme !in listOf("http", "https") &&
                    !(spec.uri.scheme in listOf("content", "file") && spec.uri == subtitleUri)
            ) {
                throw java.io.IOException("Yayın konumu desteklenmiyor.")
            }
            spec
        }
    val defaultRenderers = androidx.media3.exoplayer.DefaultRenderersFactory(context)
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
        .setLoadControl(control.build())
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
