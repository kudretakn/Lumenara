package dev.lumenara.vlc

import android.content.Context
import android.os.*
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import dev.lumenara.data.Transport
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

class UnsupportedVlcInputException(message: String = "Bu akış türü için VLC geçişi desteklenmiyor.") : IOException(message)

/** Only expose media bytes to VLC. All network traffic stays under the source's OkHttp policy. */
class VlcInput
private constructor(
    val descriptor: ParcelFileDescriptor,
    val demux: String,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** Unblock native reads without invalidating an fd VLC may still be opening. */
    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        // Only cancel the call here. The reading thread owns response/source cleanup;
        // closing an OkHttp body concurrently with read() races inside Okio.
        release()
    }

    /** Retain this descriptor until native stop() returns (or the player is released). */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { cancel() } finally { descriptor.close() }
    }

    companion object {
        /** Cancel a superseded channel's HTTP handshake immediately, before it owns an input. */
        suspend fun openCancellable(context: Context, url: String, allowHttp: Boolean): VlcInput {
            var acquired: VlcInput? = null
            try {
                return coroutineScope {
                    suspendCancellableCoroutine<VlcInput> { continuation ->
                        val opening = AtomicReference<Call?>()
                        continuation.invokeOnCancellation { opening.get()?.cancel() }
                        launch(Dispatchers.IO) {
                            try {
                                val input = open(context, url, allowHttp) { call ->
                                    opening.set(call)
                                    if (!continuation.isActive) call.cancel()
                                }
                                continuation.resume(input) { _, abandoned, _ -> abandoned.close() }
                            } catch (e: Exception) {
                                continuation.resumeWithException(e)
                            }
                        }
                    }.also { acquired = it }
                }
            } catch (e: CancellationException) {
                // coroutineScope can be cancelled after suspension resumed but before it returns.
                acquired?.close()
                throw e
            }
        }

        fun open(context: Context, url: String, allowHttp: Boolean): VlcInput =
            open(context, url, allowHttp) {}

        private fun open(context: Context, url: String, allowHttp: Boolean, opening: (Call) -> Unit): VlcInput {
            val ext = url.toHttpUrl().pathSegments.last().substringAfterLast('.').lowercase()
            val demux =
                when (ext) {
                    "ts",
                    "mts" -> "ts"
                    "mkv",
                    "webm" -> "mkv"
                    "mp4",
                    "mov",
                    "m4v" -> "mp4"
                    else -> throw UnsupportedVlcInputException()
                }
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
            val client = Transport.client(allowHttp)
            if (demux == "ts") {
                val call = client.newCall(request)
                opening(call)
                val response = call.execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("Yayın erişimi reddedildi.")
                }
                val pipe = ParcelFileDescriptor.createPipe()
                val pump = VlcInputPump(
                    source = response.body!!.byteStream(),
                    response = response,
                    output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]),
                    cancelCall = { call.cancel() },
                    onFailure = { e -> android.util.Log.w("LumenaraVlc",
                        "TS input read failed: ${e.javaClass.simpleName}") },
                )
                thread(name = "lumenara-ts", isDaemon = true) { pump.run() }
                return VlcInput(pipe[0], demux) { pump.cancel() }
            }

            val lengthCall = client.newCall(request.newBuilder().header("Range", "bytes=0-0").build())
            opening(lengthCall)
            val length = lengthCall.execute()
                    .use {
                        if (it.code != 206)
                            throw UnsupportedVlcInputException("Sağlayıcı dosya konumlandırmayı desteklemiyor.")
                        it.header("Content-Range")
                            ?.substringAfterLast('/')
                            ?.toLongOrNull()
                            ?.takeIf { size -> size > 0 }
                            ?: throw UnsupportedVlcInputException("Dosya uzunluğu alınamadı.")
                    }
            val io = HandlerThread("lumenara-vlc-range").apply { start() }
            val callback =
                object : ProxyFileDescriptorCallback() {
                    var next = -1L
                    @Volatile var call: Call? = null
                    var response: Response? = null

                    override fun onGetSize() = length

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        if (offset >= length) return 0
                        try {
                            if (offset != next || response == null) {
                                response?.close()
                                val newCall =
                                    client.newCall(
                                        request
                                            .newBuilder()
                                            .header("Range", "bytes=$offset-")
                                            .build()
                                    )
                                call = newCall
                                response =
                                    newCall.execute().also {
                                        if (
                                            it.code != 206 ||
                                                it.header("Content-Range")
                                                    ?.startsWith("bytes $offset-") != true
                                        ) {
                                            it.close()
                                            throw IOException()
                                        }
                                    }
                                next = offset
                            }
                            // ProxyFileDescriptorCallback requires a full read unless at EOF.
                            // A socket read can return only one packet; returning that short
                            // count lets FUSE treat the rest as EOF/zeroes, corrupting the MP4.
                            val read = minOf(size.toLong(), length - offset).toInt()
                            DataInputStream(response!!.body!!.byteStream()).readFully(data, 0, read)
                            next += read
                            return read
                        } catch (_: IOException) {
                            throw ErrnoException("read", OsConstants.EIO)
                        }
                    }

                    override fun onRelease() {
                        response?.close()
                        io.quitSafely()
                    }
                }
            try {
                val fd =
                    context
                        .getSystemService(StorageManager::class.java)
                        .openProxyFileDescriptor(
                            ParcelFileDescriptor.MODE_READ_ONLY,
                            callback,
                            Handler(io.looper),
                        )
                return VlcInput(fd, demux) { callback.call?.cancel() }
            } catch (e: Exception) {
                io.quitSafely()
                throw e
            }
        }
    }
}
