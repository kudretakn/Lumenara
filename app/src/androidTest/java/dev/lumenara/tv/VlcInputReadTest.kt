package dev.lumenara.tv

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.vlc.VlcInput
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class VlcInputReadTest {
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
