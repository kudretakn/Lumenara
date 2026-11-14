package dev.lumenara.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

object Transport {
    /** Internal marker, consumed before any bytes reach the server. */
    const val IMAGE_HTTP_HEADER = "X-Lumenara-Image-Http"

    private data class ImagePermission(val allowHttp: Boolean)

    /** Dedicated, credential-free client; enforce permission again on every redirect hop. */
    fun imageClient(): OkHttpClient = base.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .authenticator(okhttp3.Authenticator.NONE)
        .proxyAuthenticator(okhttp3.Authenticator.NONE)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            val permission = ImagePermission(request.header(IMAGE_HTTP_HEADER) == "true")
            if (!imageUrlAllowed(request.url, permission.allowHttp)) throw IOException("Image URL blocked")
            chain.proceed(request.newBuilder().removeHeader(IMAGE_HTTP_HEADER)
                .tag(ImagePermission::class.java, permission).build())
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!imageUrlAllowed(request.url, request.tag(ImagePermission::class.java)?.allowHttp == true))
                throw IOException("Image URL blocked")
            chain.proceed(request.newBuilder().removeHeader("Cookie").removeHeader("Cookie2")
                .removeHeader("Authorization").removeHeader("Proxy-Authorization").build())
        }
        .build()

    internal fun imageUrlAllowed(url: okhttp3.HttpUrl, allowHttp: Boolean): Boolean =
        (url.isHttps || allowHttp) && url.username.isEmpty() && url.password.isEmpty()

    private val base =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

    fun client(allowHttp: Boolean): OkHttpClient =
        base
            .newBuilder()
            // A source may cross HTTP/HTTPS only when its HTTP compatibility is enabled.
            // HTTPS-only clients still reject an initial HTTP URL and every downgrade hop.
            .followSslRedirects(allowHttp)
            .addInterceptor { chain ->
                if (!allowHttp && !chain.request().url.isHttps) throw IOException("HTTP kapalı.")
                chain.proceed(chain.request())
            }
            .build()

    fun <T> read(url: String, allowHttp: Boolean, parse: (java.io.InputStream, String) -> T): T {
        val request = Request.Builder().url(url).get().build()
        return client(allowHttp).newCall(request).execute().use { response ->
            if (response.code in listOf(300, 301, 302, 303, 307, 308)) {
                val target = response.header("Location")?.let { response.request.url.resolve(it) }
                if (!allowHttp && target != null && !target.isHttps)
                    throw SourceFailure(
                        "Sağlayıcı HTTP adresine yönlendiriyor. Bu kaynak için HTTP uyumluluğunu açın."
                    )
                throw SourceFailure(
                    "Sağlayıcı geçerli bir yönlendirme adresi vermedi (HTTP ${response.code}). Liste adresini kontrol edin."
                )
            }
            if (!response.isSuccessful)
                throw SourceFailure("Sağlayıcı HTTP ${response.code} yanıtını verdi.")
            parse(
                bounded(checkNotNull(response.body).byteStream()),
                response.request.url.toString(),
            )
        }
    }
}

class SourceFailure(message: String) : Exception(message)
