package dev.lumenara.vlc

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class Wp09VlcInputPolicyTest {
    @Test fun cancelledReadCannotCreateOrPublishAnotherCall() {
        val created = AtomicInteger()
        val cancelled = AtomicInteger()
        val gate = Wp09CallGate<Int> { cancelled.incrementAndGet() }
        gate.create { created.incrementAndGet() }
        gate.cancel()
        gate.cancel()
        repeat(10) {
            try {
                gate.create { created.incrementAndGet() }
                fail("Read after cancellation created a call")
            } catch (_: IOException) { }
        }
        assertEquals(1, created.get())
        assertEquals(1, cancelled.get())
    }

    @Test(timeout = 5_000) fun cancellationSerializesWithCallCreationAlreadyInProgress() {
        val creating = CountDownLatch(1)
        val publish = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val created = AtomicInteger()
        val gate = Wp09CallGate<Int> { cancelled.incrementAndGet() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val reader = executor.submit<Int> {
                gate.create {
                    creating.countDown()
                    check(publish.await(2, TimeUnit.SECONDS))
                    created.incrementAndGet()
                }
            }
            assertTrue(creating.await(2, TimeUnit.SECONDS))
            val cancel = executor.submit { cancelStarted.countDown(); gate.cancel() }
            assertTrue(cancelStarted.await(2, TimeUnit.SECONDS))
            publish.countDown()
            assertEquals(1, reader.get(2, TimeUnit.SECONDS).toInt())
            cancel.get(2, TimeUnit.SECONDS)
            try { gate.create { created.incrementAndGet() }; fail("Call after cancel") } catch (_: IOException) { }
            assertEquals(1, created.get())
            assertEquals(1, cancelled.get())
        } finally { publish.countDown(); executor.shutdownNow() }
    }

    @Test fun sniffRecognizesOnlyBoundedTsAndMp4Signatures() {
        val ts = ByteArray(Wp09VlcSniff.MAX_BYTES)
        for (offset in listOf(0, 188, 376)) ts[offset] = 0x47
        assertEquals("ts", Wp09VlcSniff.demux(ts))
        assertNull(Wp09VlcSniff.demux(ts.copyOf(376)))
        assertEquals("mp4", Wp09VlcSniff.demux(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109)))
        for (body in listOf("#EXTM3U\n#EXT-X-VERSION:3\n", "<?xml version=\"1.0\"?><MPD></MPD>", "unknown"))
            assertNull(Wp09VlcSniff.demux(body.toByteArray()))
    }

    @Test fun contentRangeValidatesBoundsUnitsAndTotal() {
        assertEquals(Wp09ContentRange(0, 0, 100), Wp09ContentRange.parse("bytes 0-0/100"))
        assertEquals(Wp09ContentRange(42, 99, 100), Wp09ContentRange.parse("bytes 42-99/100"))
        for (value in listOf(null, "bytes 0-0/*", "bytes 4-3/100", "bytes 0-100/100",
                "bytes 0-0/0", "items 0-1/2", "bytes -1-1/2", "bytes 0-1/999999999999999999999"))
            assertNull(value, Wp09ContentRange.parse(value))
    }
}
