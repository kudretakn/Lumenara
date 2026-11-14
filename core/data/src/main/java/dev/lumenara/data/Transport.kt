package dev.lumenara.data

import java.io.IOException
import java.io.File
import java.io.Closeable
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object Transport {
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
            validate(response, allowHttp)
            parse(
                bounded(checkNotNull(response.body).byteStream()),
                response.request.url.toString(),
            )
        }
    }

    // Keep large bodies on disk: equality must be known before parsing or opening a DB transaction.
    internal fun download(url: String, allowHttp: Boolean, cache: File, previous: FetchState = FetchState(),
        limit: Long = 64L * 1024 * 1024, checkActive: () -> Unit = {}): Download {
        checkActive()
        val request = Request.Builder().url(url).apply {
            if (previous.etag.isNotEmpty()) header("If-None-Match", previous.etag)
            if (previous.modified.isNotEmpty()) header("If-Modified-Since", previous.modified)
        }.build()
        return client(allowHttp).newCall(request).execute().use { response ->
            if (response.code == 304) {
                if (previous.etag.isEmpty() && previous.modified.isEmpty())
                    throw SourceFailure("Sağlayıcı beklenmeyen HTTP 304 yanıtını verdi.")
                return@use Download(null, response.request.url.toString(), previous.copy(
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
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
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
