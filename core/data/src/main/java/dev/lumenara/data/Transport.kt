package dev.lumenara.data

import java.io.IOException
import java.io.File
import java.io.Closeable
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

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
        return read(url, allowHttp, null, parse)
    }

    internal fun <T> read(url: String, allowHttp: Boolean, calls: ImportCalls?,
        parse: (java.io.InputStream, String) -> T): T {
        val request = Request.Builder().url(url).get().build()
        return execute(request, allowHttp, calls) { response ->
            validate(response, allowHttp)
            parse(
                bounded(checkNotNull(response.body).byteStream()),
                response.request.url.toString(),
            )
        }
    }

    internal fun resolveBase(address: String, allowHttp: Boolean, calls: ImportCalls? = null): String {
        try {
            val url = address.trim().toHttpUrl()
            if (url.username.isNotEmpty() || url.password.isNotEmpty())
                throw SourceFailure("Sunucu adresindeki kullanıcı bilgilerini kaldırın; kullanıcı adı ve şifre alanlarını kullanın.")
            if (!url.isHttps && !allowHttp)
                throw SourceFailure("Bu adres HTTP kullanıyor. HTTP uyumluluğunu açın.")
            return execute(Request.Builder().url(url).build(), allowHttp, calls) { response ->
                // Many Xtream hosts have no web page at their root (401/403/404).
                // Resolve the URL here; the account endpoint validates the provider next.
                if (response.code in 300..399 || response.code >= 500) validate(response, allowHttp)
                val resolved = response.request.url.newBuilder()
                    .username("").password("").query(null).fragment(null)
                val segments = response.request.url.pathSegments
                if (segments.last().contains('.')) resolved.removePathSegment(segments.lastIndex)
                resolved.build().toString().trimEnd('/')
            }
        } catch (error: SourceFailure) {
            throw SourceFailure("Sunucu adresi çözümlenemedi. ${error.message}")
        } catch (_: Exception) {
            throw SourceFailure("Sunucu adresi çözümlenemedi. Adresi ve bağlantıyı kontrol edin; HTTP adresi için HTTP uyumluluğunu açın.")
        }
    }

    private fun <T> execute(request: Request, allowHttp: Boolean, calls: ImportCalls?,
        timeoutMillis: Long? = 180_000L, read: (Response) -> T): T {
        val call = client(allowHttp).newCall(request)
        if (timeoutMillis != null) call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        calls?.track(call)
        try {
            return call.execute().use(read)
        } catch (_: java.io.InterruptedIOException) {
            throw SourceFailure("Sunucu isteği zaman aşımına uğradı. Yeniden deneyin veya başka bir sunucu adresi kullanın.")
        } finally { calls?.release(call) }
    }

    // Keep large bodies on disk: equality must be known before parsing or opening a DB transaction.
    internal fun download(url: String, allowHttp: Boolean, cache: File, previous: FetchState = FetchState(),
        limit: Long = 64L * 1024 * 1024, checkActive: () -> Unit = {}, calls: ImportCalls? = null,
        progress: (Long) -> Unit = {}, stallWindowMillis: Long = 60_000L,
        stallMinimumBytes: Long = 64L * 1024): Download {
        require(stallWindowMillis > 0 && stallMinimumBytes > 0)
        checkActive()
        val request = Request.Builder().url(url).apply {
            if (previous.etag.isNotEmpty()) header("If-None-Match", previous.etag)
            if (previous.modified.isNotEmpty()) header("If-Modified-Since", previous.modified)
        }.build()
        return execute(request, allowHttp, calls, timeoutMillis = null) { response ->
            checkActive()
            if (response.code == 304) {
                if (previous.digest.isEmpty() || (previous.etag.isEmpty() && previous.modified.isEmpty()))
                    throw SourceFailure("Sağlayıcı beklenmeyen HTTP 304 yanıtını verdi.")
                // A redirect can change the base of relative playlist entries. A 304 alone
                // cannot establish that those entries still describe the imported catalog.
                if (previous.finalUrl != response.request.url.toString()) {
                    response.close()
                    return@execute download(url, allowHttp, cache, limit = limit, checkActive = checkActive,
                        calls = calls, progress = progress, stallWindowMillis = stallWindowMillis,
                        stallMinimumBytes = stallMinimumBytes)
                }
                return@execute Download(null, response.request.url.toString(), previous.copy(
                    etag = response.header("ETag") ?: previous.etag,
                    modified = response.header("Last-Modified") ?: previous.modified,
                ), true)
            }
            validate(response, allowHttp)
            val file = File.createTempFile("refresh-", ".body", cache)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(bounded(checkNotNull(response.body).byteStream(), limit), digest).use { input ->
                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        var reported = 0L
                        val stall = DownloadStall(stallWindowMillis, stallMinimumBytes)
                        progress(0)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            stall.received(count.coerceAtLeast(0))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            total += count
                            if (total - reported >= 256 * 1024) {
                                progress(total)
                                reported = total
                            }
                        }
                        progress(total)
                    }
                }
                checkActive()
                val state = FetchState(response.header("ETag").orEmpty(), response.header("Last-Modified").orEmpty(),
                    digest.digest().joinToString("") { "%02x".format(it) })
                // Relative M3U entries depend on the final URL as well as the body.
                Download(file, response.request.url.toString(), state,
                    state.digest == previous.digest && previous.finalUrl == response.request.url.toString())
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }

    /** Check a rolling window, so an early burst cannot hide a later trickle. */
    private class DownloadStall(windowMillis: Long, private val minimumBytes: Long) {
        private val windowNanos = TimeUnit.MILLISECONDS.toNanos(windowMillis)
        private val started = System.nanoTime()
        private val arrivals = ArrayDeque<Pair<Long, Int>>()
        private var bytes = 0L

        fun received(count: Int) {
            val now = System.nanoTime()
            while (arrivals.isNotEmpty() && now - arrivals.first.first >= windowNanos) {
                bytes -= arrivals.removeFirst().second
            }
            if (count > 0) {
                arrivals.addLast(now to count)
                bytes += count
            }
            // Retain only enough recent arrivals to establish the minimum throughput.
            while (arrivals.isNotEmpty() && bytes - arrivals.first.second >= minimumBytes) {
                bytes -= arrivals.removeFirst().second
            }
            if (now - started >= windowNanos && bytes < minimumBytes)
                throw java.io.InterruptedIOException("Download stalled")
        }
    }

    private fun validate(response: Response, allowHttp: Boolean) {
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
    }
}

internal data class FetchState(val etag: String = "", val modified: String = "", val digest: String = "", val finalUrl: String = "") {
    fun encode(): String = org.json.JSONObject().put("etag", etag).put("modified", modified)
        .put("digest", digest).put("url", finalUrl).toString()

    companion object {
        fun decode(value: String): FetchState = runCatching {
            org.json.JSONObject(value).let {
                FetchState(it.optString("etag"), it.optString("modified"), it.optString("digest"), it.optString("url"))
            }
        }.getOrDefault(FetchState())
    }
}

internal class Download(private val file: File?, val finalUrl: String, state: FetchState, val unchanged: Boolean) : Closeable {
    val state = state.copy(finalUrl = finalUrl)
    suspend fun importIfChanged(applyImport: suspend () -> Unit): Boolean {
        if (unchanged) return false
        applyImport()
        return true
    }
    fun <T> read(parse: (java.io.InputStream, String) -> T): T =
        checkNotNull(file).inputStream().buffered().use { parse(it, finalUrl) }
    override fun close() { file?.delete() }
}

class SourceFailure(message: String) : Exception(message)
