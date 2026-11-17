package dev.lumenara.vlc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class VlcInputPumpTest {
    @Test fun cancellationDuringReadLeavesCleanupToPumpAndDoesNotDrain() {
        val reading = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val reads = AtomicInteger()
        val responseClosedOn = AtomicReference<Thread>()
        val writerClosedOn = AtomicReference<Thread>()
        val failures = AtomicInteger()
        val source = object : InputStream() {
            override fun read(): Int = error("Use bulk reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads.incrementAndGet()
                reading.countDown()
                check(callCancelled.await(5, TimeUnit.SECONDS))
                // Keep read active even after cancel() returns to verify it cannot close us.
                check(finishRead.await(5, TimeUnit.SECONDS))
                throw IOException("Socket cancelled")
            }
            override fun close() {
                check(finishRead.count == 0L) { "Closed concurrently with read" }
                responseClosedOn.set(Thread.currentThread())
            }
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { writerClosedOn.set(Thread.currentThread()) }
        }
        val pump = VlcInputPump(source, source, output,
            cancelCall = { cancellations.incrementAndGet(); callCancelled.countDown() },
            onFailure = { failures.incrementAndGet() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = executor.submit<Thread> { pump.run(); Thread.currentThread() }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            pump.cancel()
            pump.cancel()
            assertEquals(1, cancellations.get())
            assertNull(responseClosedOn.get())
            assertNull(writerClosedOn.get())
            finishRead.countDown()
            val owner = worker.get(5, TimeUnit.SECONDS)
            assertSame(owner, responseClosedOn.get())
            assertSame(owner, writerClosedOn.get())
            assertEquals(1, reads.get())
            assertEquals(0, output.size())
            assertEquals(0, failures.get())
        } finally {
            callCancelled.countDown()
            finishRead.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun cancellationBeforeStartSkipsBodyAndStillClosesBothResources() {
        var responseClosed = false
        var writerClosed = false
        var cancellations = 0
        val source = object : InputStream() {
            override fun read(): Int = error("Cancelled body must not be drained")
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { writerClosed = true }
        }
        val pump = VlcInputPump(source, Closeable { responseClosed = true }, output,
            cancelCall = { cancellations++ }, onFailure = { throw AssertionError(it) })
        pump.cancel()
        assertFalse(responseClosed)
        assertFalse(writerClosed)
        pump.run()
        assertEquals(1, cancellations)
        assertTrue(responseClosed)
        assertTrue(writerClosed)
    }

    @Test fun cancelledReadReturningBufferedBytesDoesNotWriteOrReadAgain() {
        val reading = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val reads = AtomicInteger()
        val source = object : InputStream() {
            override fun read(): Int = error("Use bulk reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads.incrementAndGet()
                reading.countDown()
                check(callCancelled.await(5, TimeUnit.SECONDS))
                bytes[offset] = 42
                return 1
            }
        }
        val pump = VlcInputPump(source, source, output,
            cancelCall = { callCancelled.countDown() }, onFailure = { throw AssertionError(it) })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = executor.submit { pump.run() }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            pump.cancel()
            worker.get(5, TimeUnit.SECONDS)
            assertEquals(1, reads.get())
            assertEquals(0, output.size())
        } finally {
            callCancelled.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun responseCleanupWaitsForSocketCancellationToFinish() {
        val reading = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        val finishCancel = CountDownLatch(1)
        val writerClosed = CountDownLatch(1)
        val responseClosedOn = AtomicReference<Thread>()
        val source = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(cancelling.await(5, TimeUnit.SECONDS))
                throw IOException("Socket cancelled")
            }
            override fun close() { responseClosedOn.set(Thread.currentThread()) }
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { writerClosed.countDown() }
        }
        val pump = VlcInputPump(source, source, output,
            cancelCall = {
                cancelling.countDown()
                check(finishCancel.await(5, TimeUnit.SECONDS))
            }, onFailure = { throw AssertionError(it) })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<Thread> { pump.run(); Thread.currentThread() }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val cancellation = executor.submit { pump.cancel() }
            assertTrue(writerClosed.await(5, TimeUnit.SECONDS))
            assertNull(responseClosedOn.get())
            finishCancel.countDown()
            cancellation.get(5, TimeUnit.SECONDS)
            assertSame(worker.get(5, TimeUnit.SECONDS), responseClosedOn.get())
        } finally {
            cancelling.countDown()
            finishCancel.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun normalEofCopiesAllBytesAndClosesBothResources() {
        val bytes = ByteArray(20_000) { it.toByte() }
        var responseClosed = false
        var writerClosed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { writerClosed = true }
        }
        VlcInputPump(ByteArrayInputStream(bytes), Closeable { responseClosed = true }, output,
            cancelCall = { error("Unexpected cancellation") },
            onFailure = { throw AssertionError(it) }).run()
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(responseClosed)
        assertTrue(writerClosed)
    }

    @Test fun uncancelledReadFailureIsReportedAfterCleanup() {
        val failure = IOException("Network failure")
        var responseClosed = false
        var writerClosed = false
        var reported: IOException? = null
        val source = object : InputStream() {
            override fun read(): Int = throw failure
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { writerClosed = true }
        }
        VlcInputPump(source, Closeable { responseClosed = true }, output,
            cancelCall = { error("Unexpected cancellation") },
            onFailure = { reported = it; assertTrue(responseClosed && writerClosed) }).run()
        assertSame(failure, reported)
    }
}
