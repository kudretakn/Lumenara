package dev.lumenara.vlc

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the HTTP response and pipe writer; only [run]'s thread may close them. */
internal class VlcInputPump(
    private val source: InputStream,
    private val response: Closeable,
    private val output: OutputStream,
    private val cancelCall: () -> Unit,
    private val onFailure: (IOException) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)
    private val cancellationLock = Any()

    fun cancel() = synchronized(cancellationLock) {
        if (cancelled.compareAndSet(false, true)) {
            // OkHttp Call.cancel is thread-safe and interrupts a blocked socket read.
            // Cancel the socket before the pump closes the body, preventing network drain.
            cancelCall()
        }
    }

    fun run() {
        try {
            response.use {
                try {
                    output.use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        // Do not keep consuming buffered body bytes after cancellation.
                        while (!cancelled.get()) {
                            val count = source.read(buffer)
                            if (count < 0 || cancelled.get()) break
                            out.write(buffer, 0, count)
                        }
                    }
                } finally {
                    // A cancelled read may exit before Call.cancel itself returns. Wait for
                    // socket cancellation to finish before closing the body (which can discard).
                    synchronized(cancellationLock) { }
                }
            }
        } catch (e: IOException) {
            if (!cancelled.get()) onFailure(e)
        }
    }
}
