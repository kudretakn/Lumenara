package dev.lumenara.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DecoderCapacityTest {
    @Test fun probingIsLazyAndCachedAcrossConcurrentRequests() {
        val calls = AtomicInteger()
        val capacity = DecoderCapacity { calls.incrementAndGet(); 3 }
        assertEquals(0, calls.get())
        val threads = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map { threads.submit<Int> { start.await(); capacity.get() } }
            start.countDown()
            results.forEach { assertEquals(3, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(3, capacity.get())
            assertEquals(1, calls.get())
        } finally { threads.shutdownNow() }
    }

    @Test fun brokenVendorProbeCachesSafeSingleDecoderFallback() {
        var calls = 0
        val capacity = DecoderCapacity { calls++; error("vendor codec error") }
        repeat(2) { assertEquals(1, capacity.get()) }
        assertEquals(1, calls)
        assertEquals(1, DecoderCapacity { 0 }.get())
        assertEquals(4, DecoderCapacity { 16 }.get())
    }
}
