package dev.lumenara.tv

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.vlc.UnsupportedVlcInputException
import dev.lumenara.vlc.VlcInput
import java.io.DataInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class Wp09VlcInputTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 10_000) fun cancelDuringRangeReadRejectsLaterSeekWithoutAnotherRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/65536")
                .setBody("x"))
            // Headers arrive, but a full proxy read cannot finish before cancellation.
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-65535/65536")
                .setBody("x").setHeader("Content-Length", 65536))
            // A regression that opens another Call must fail promptly, without a blocked fixture.
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 32768-65535/65536")
                .setBody(Buffer().write(ByteArray(32768))))
            server.start()
            VlcInput.open(context, server.url("/race.mp4").toString(), true).use { input ->
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val read = executor.submit<Boolean> {
                        try {
                            Os.pread(input.descriptor.fileDescriptor, ByteArray(4096), 0, 4096, 0)
                            false
                        } catch (_: ErrnoException) { true }
                    }
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    input.cancel()
                    assertTrue("Cancellation unblocks native read", read.get(2, TimeUnit.SECONDS))
                    try {
                        Os.pread(input.descriptor.fileDescriptor, ByteArray(4096), 0, 4096, 32768)
                        fail("Cancelled seek must fail")
                    } catch (_: ErrnoException) { }
                    assertNull("No post-cancel Range request", server.takeRequest(200, TimeUnit.MILLISECONDS))
                    assertEquals(2, server.requestCount)
                    assertTrue("fd remains owned until native stop", input.descriptor.fileDescriptor.valid())
                } finally { input.cancel(); executor.shutdownNow() }
            }
        }
    }

    @Test(timeout = 10_000) fun extensionlessTsProbePreservesPrefixAndUsesOneStream() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("lumenara-test.ts")
            .use { it.readBytes() }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.start()
            VlcInput.open(context, server.url("/stream.php?id=1").toString(), true).use { input ->
                assertEquals("ts", input.demux)
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(input.descriptor.fileDescriptor)).use {
                    val actual = ByteArray(bytes.size)
                    DataInputStream(it).readFully(actual)
                    assertArrayEquals(bytes, actual)
                }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test(timeout = 10_000) fun extensionlessMp4SupportsSeekAfterBoundedProbe() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("lumenara-test.mp4")
            .use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(bytes))
                    val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                    val end = range.substringAfter('-').toIntOrNull() ?: bytes.lastIndex
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                        .setBody(Buffer().write(bytes, start, end - start + 1))
                }
            }
            server.start()
            VlcInput.open(context, server.url("/stream?id=2").toString(), true).use { input ->
                assertEquals("mp4", input.demux)
                for (offset in listOf(bytes.size - 37, 0, bytes.size / 2)) {
                    val actual = ByteArray(37)
                    assertEquals(actual.size, Os.pread(input.descriptor.fileDescriptor, actual, 0, actual.size, offset.toLong()))
                    assertArrayEquals(bytes.copyOfRange(offset, offset + actual.size), actual)
                }
            }
        }
    }

    @Test(timeout = 10_000) fun manifestsAndUnrecognizedBoundedPrefixAreRejected() {
        MockWebServer().use { server ->
            server.start()
            for (path in listOf("/live.m3u8", "/live.mpd")) {
                try { VlcInput.open(context, server.url(path).toString(), true).close(); fail(path) }
                catch (_: UnsupportedVlcInputException) { }
            }
            assertEquals(0, server.requestCount)
            for (type in listOf("application/vnd.apple.mpegurl", "application/dash+xml", "application/octet-stream")) {
                server.enqueue(MockResponse().setHeader("Content-Type", type)
                    .setBody(Buffer().write(ByteArray(564)))
                    .setHeader("Content-Length", 1024 * 1024))
                try { VlcInput.open(context, server.url("/unknown").toString(), true).close(); fail(type) }
                catch (_: UnsupportedVlcInputException) { }
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test(timeout = 10_000) fun wrongRangeOffsetAndChangedTotalFailWithoutReturningCorruptBytes() {
        for (range in listOf("bytes 1-65535/65536", "bytes 0-65535/65537")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/65536").setBody("x"))
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", range).setBody("bad"))
                server.start()
                VlcInput.open(context, server.url("/bad.mp4").toString(), true).use { input ->
                    try {
                        Os.pread(input.descriptor.fileDescriptor, ByteArray(4096), 0, 4096, 0)
                        fail("Invalid Content-Range must fail")
                    } catch (_: ErrnoException) { }
                }
            }
        }
    }
}
