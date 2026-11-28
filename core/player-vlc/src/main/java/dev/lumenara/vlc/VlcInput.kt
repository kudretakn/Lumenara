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
    @Synchronized fun cancel() {
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
                        val calls = Wp09CallGate<Call> { it.cancel() }
                        continuation.invokeOnCancellation { calls.cancel() }
                        launch(Dispatchers.IO) {
                            try {
                                val input = open(context, url, allowHttp, calls)
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
            open(context, url, allowHttp, Wp09CallGate { it: Call -> it.cancel() })

        private fun open(context: Context, url: String, allowHttp: Boolean, calls: Wp09CallGate<Call>): VlcInput {
            val ext = url.toHttpUrl().pathSegments.last().substringAfterLast('.').lowercase()
            var demux =
                when (ext) {
                    "ts",
                    "mts" -> "ts"
                    "mkv",
                    "webm" -> "mkv"
                    "mp4",
                    "mov",
                    "m4v" -> "mp4"
                    "m3u", "m3u8", "mpd" -> throw UnsupportedVlcInputException()
                    else -> null
                }
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
            val client = Transport.client(allowHttp)
            fun execute(request: Request): Response = calls.create { client.newCall(request) }.execute()
            fun rejectManifest(response: Response) {
                val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (type in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl",
                        "audio/mpegurl", "audio/x-mpegurl", "application/dash+xml")) {
                    calls.cancel()
                    response.close()
                    throw UnsupportedVlcInputException()
                }
            }
            var stream: Response? = null
            if (demux == null || demux == "ts") {
                val response = execute(request)
                try {
                    if (!response.isSuccessful) throw IOException("Yayın erişimi reddedildi.")
                    rejectManifest(response)
                    if (demux == null) {
                        val source = checkNotNull(response.body).source()
                        source.request(12)
                        demux = Wp09VlcSniff.demux(source.buffer.clone().readByteArray(minOf(12L, source.buffer.size)))
                        if (demux == null) {
                            source.request(Wp09VlcSniff.MAX_BYTES.toLong())
                            demux = Wp09VlcSniff.demux(source.buffer.clone()
                                .readByteArray(minOf(Wp09VlcSniff.MAX_BYTES.toLong(), source.buffer.size)))
                        }
                        if (demux == null) throw UnsupportedVlcInputException()
                    }
                    if (demux == "ts") stream = response
                    else {
                        // A sniff must not drain an unbounded file response on close.
                        // Cancel just this probe, leaving the input gate available for Range.
                        calls.cancelCurrent()
                        response.close()
                    }
                } catch (e: Exception) {
                    calls.cancel()
                    response.close()
                    throw e
                }
            }
            val selectedDemux = checkNotNull(demux)
            if (selectedDemux == "ts") {
                val response = checkNotNull(stream)
                val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: Exception) {
                    calls.cancel()
                    response.close()
                    throw e
                }
                try {
                    val pump = VlcInputPump(
                        source = response.body!!.byteStream(),
                        response = response,
                        output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]),
                        cancelCall = { calls.cancel() },
                        onFailure = { e -> android.util.Log.w("LumenaraVlc",
                            "TS input read failed: ${e.javaClass.simpleName}") },
                    )
                    thread(name = "lumenara-ts", isDaemon = true) { pump.run() }
                    return VlcInput(pipe[0], selectedDemux) { pump.cancel() }
                } catch (e: Exception) {
                    calls.cancel()
                    response.close()
                    pipe.forEach { it.close() }
                    throw e
                }
            }

            val length = execute(request.newBuilder().header("Range", "bytes=0-0").build()).use {
                rejectManifest(it)
                val range = Wp09ContentRange.parse(it.header("Content-Range"))
                if (it.code != 206 || range == null || range.first != 0L || range.last != 0L)
                    throw UnsupportedVlcInputException("Sağlayıcı dosya konumlandırmayı desteklemiyor.")
                range.total
            }
            val io = HandlerThread("lumenara-vlc-range").apply { start() }
            val callback =
                object : ProxyFileDescriptorCallback() {
                    var next = -1L
                    var response: Response? = null

                    override fun onGetSize() = length

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        try {
                            calls.checkActive()
                            if (offset >= length) return 0
                            if (offset != next || response == null) {
                                calls.cancelCurrent()
                                response?.close()
                                response = null
                                val newCall =
                                    calls.create { client.newCall(
                                        request
                                            .newBuilder()
                                            .header("Range", "bytes=$offset-")
                                            .build()
                                    ) }
                                response =
                                    newCall.execute().also {
                                        val range = Wp09ContentRange.parse(it.header("Content-Range"))
                                        if (it.code != 206 || range == null || range.first != offset ||
                                            range.total != length) {
                                            calls.cancelCurrent()
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
                        calls.cancel()
                        try { response?.close() } finally { io.quitSafely() }
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
                return VlcInput(fd, selectedDemux) { calls.cancel() }
            } catch (e: Exception) {
                calls.cancel()
                io.quitSafely()
                throw e
            }
        }
    }
}
