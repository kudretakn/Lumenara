package dev.lumenara.tv

import android.system.Os
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.vlc.VlcInput
import java.io.DataInputStream
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class VlcInputReadTest {
    @Test(timeout = 10_000) fun cancellationKeepsPipeDescriptorOwnedUntilNativeRelease() {
        MockWebServer().use { server ->
            val prefix = ByteArray(188 * 8) { it.toByte() }
            // Send a prefix, then leave the declared body unfinished on the open socket.
            // The pump cannot finish naturally before cancellation; no sleeps or delays.
            server.enqueue(MockResponse().setBody(Buffer().write(prefix))
                .setHeader("Content-Length", prefix.size + 1))
            server.start()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val input = VlcInput.open(context, server.url("/live.ts").toString(), true)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(
                    ParcelFileDescriptor.dup(input.descriptor.fileDescriptor)
                ).use { reader ->
                    val actual = ByteArray(prefix.size)
                    DataInputStream(reader).readFully(actual)
                    assertArrayEquals(prefix, actual)
                    input.cancel()
                    input.cancel()
                    // EOF acknowledges that the pump has exited and closed its writer.
                    assertEquals(-1, reader.read())
                }
                // Native fd:// access duplicates the descriptor asynchronously after play().
                // Even cancellation before that open must leave a valid descriptor to duplicate.
                ParcelFileDescriptor.dup(input.descriptor.fileDescriptor).use { native ->
                    assertTrue(native.fileDescriptor.valid())
                    Os.fstat(native.fileDescriptor)
                }
                assertTrue(input.descriptor.fileDescriptor.valid())
            } finally {
                input.close()
                input.close()
            }
            assertFalse(input.descriptor.fileDescriptor.valid())
        }
    }

    @Test fun fragmentedHttpReadsPreserveBytesAcrossSeeksAndEof() {
        val bytes = ByteArray(96 * 1024 + 37) { (it * 31 + 7).toByte() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = checkNotNull(request.getHeader("Range")).removePrefix("bytes=")
                    val start = range.substringBefore('-').toInt()
                    val end = range.substringAfter('-').toIntOrNull() ?: bytes.lastIndex
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                        .setBody(Buffer().write(bytes, start, end - start + 1))
                        // Force socket reads smaller than the kernel's proxy read.
                        .throttleBody(1024, 5, TimeUnit.MILLISECONDS)
                }
            }
            server.start()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            VlcInput.open(context, server.url("/fragmented.mp4").toString(), true).use { input ->
                fun assertRead(offset: Int, size: Int) {
                    val expected = bytes.copyOfRange(offset, minOf(offset + size, bytes.size))
                    val actual = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val count = Os.pread(input.descriptor.fileDescriptor, actual, read,
                            size - read, (offset + read).toLong())
                        if (count == 0) break
                        read += count
                    }
                    assertEquals("Length at offset $offset", expected.size, read)
                    assertArrayEquals("Bytes at offset $offset", expected, actual.copyOf(read))
                }
                assertRead(32 * 1024 + 13, 48 * 1024)
                assertRead(0, bytes.size)
                assertRead(bytes.size - 37, 4096)
                assertRead(bytes.size, 4096)
            }
        }
    }
}
