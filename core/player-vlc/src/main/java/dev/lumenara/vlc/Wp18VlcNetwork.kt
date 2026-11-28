package dev.lumenara.vlc

import dev.lumenara.player.api.PlaybackNetwork
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Standalone input-reader compatibility. Production playback injects the app's Transport. */
internal object Wp18VlcNetwork : PlaybackNetwork {
    private val base by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).build()
    }
    override fun client(allowHttp: Boolean) = base.newBuilder()
        .followSslRedirects(allowHttp)
        .addInterceptor { chain ->
            if (!allowHttp && !chain.request().url.isHttps) throw IOException("HTTP kapalı.")
            chain.proceed(chain.request())
        }.build()
}
