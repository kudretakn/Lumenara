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
        // Releasing an OkHttp response may perform socket I/O. Callers can be on
        // the main thread, so dispatch the release when needed.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            thread(name = "lumenara-vlc-close", isDaemon = true) { release() }
        } else {
            release()
        }
    }

    /** The owner must retain this descriptor until the native player has been released. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { cancel() } finally { descriptor.close() }
    }

    companion object {
        fun open(context: Context, url: String, allowHttp: Boolean): VlcInput {
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
                    else -> throw IOException("Bu akış türü için VLC geçişi desteklenmiyor.")
                }
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
            val client = Transport.client(allowHttp)
            if (demux == "ts") {
                val call = client.newCall(request)
                val response = call.execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("Yayın erişimi reddedildi.")
                }
                val pipe = ParcelFileDescriptor.createPipe()
                thread(name = "lumenara-ts", isDaemon = true) {
                    response.use {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                            try {
                                it.body?.byteStream()?.copyTo(out)
                            } catch (e: IOException) {
                                if (!call.isCanceled()) android.util.Log.w("LumenaraVlc",
                                    "TS input read failed: ${e.javaClass.simpleName}")
                            }
                        }
                    }
                }
                return VlcInput(pipe[0], demux) {
                    call.cancel()
                    response.close()
                }
            }

            val length =
                client
                    .newCall(request.newBuilder().header("Range", "bytes=0-0").build())
                    .execute()
                    .use {
                        if (it.code != 206)
                            throw IOException("Sağlayıcı dosya konumlandırmayı desteklemiyor.")
                        it.header("Content-Range")
                            ?.substringAfterLast('/')
                            ?.toLongOrNull()
                            ?.takeIf { size -> size > 0 }
                            ?: throw IOException("Dosya uzunluğu alınamadı.")
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
