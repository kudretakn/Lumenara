package dev.lumenara.vlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VlcRuntimeTest {
    @Test
    fun playbackSharesLazilyCreatedRuntime() {
        var creations = 0
        val runtime = VlcRuntime {
            creations++
            Any() // Stand-in for the native LibVLC instance.
        }
        assertEquals("Creating the holder must not load native code", 0, creations)

        val firstPlaybackLibrary = runtime.get()
        val secondPlaybackLibrary = runtime.get()

        assertSame("Playbacks must share LibVLC", firstPlaybackLibrary, secondPlaybackLibrary)
        assertEquals("Two playbacks must create LibVLC exactly once", 1, creations)
    }

    @Test
    fun warmupQueuesOnlyOnceAndDoesNotInitializeOnCaller() {
        var creations = 0
        val tasks = ArrayList<Runnable>()
        val executor = Executor { tasks.add(it) }
        val runtime = VlcRuntime { creations++; Any() }

        runtime.warmUp(executor)
        runtime.warmUp(executor)
        assertEquals(0, creations)
        assertEquals(1, tasks.size)
        tasks.removeAt(0).run()
        val warmed = runtime.get()
        runtime.warmUp(executor)

        assertSame(warmed, runtime.get())
        assertEquals(1, creations)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun playbackRacingWarmupWaitsForTheSameRuntime() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val creations = AtomicInteger()
        val library = Any()
        val runtime = VlcRuntime {
            creations.incrementAndGet()
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            library
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            runtime.warmUp(executor)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val playback = executor.submit<Any> { runtime.get() }
            assertFalse(playback.isDone)
            finish.countDown()
            assertSame(library, playback.get(5, TimeUnit.SECONDS))
            assertEquals(1, creations.get())
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedWarmupCanBeRetriedByPlayback() {
        var attempts = 0
        val tasks = ArrayList<Runnable>()
        val runtime = VlcRuntime {
            check(++attempts > 1) { "First native initialization failed" }
            Any()
        }
        runtime.warmUp(Executor { tasks.add(it) })
        try {
            tasks.single().run()
            org.junit.Assert.fail("The first warmup must fail")
        } catch (_: IllegalStateException) { }

        assertSame(runtime.get(), runtime.get())
        assertEquals(2, attempts)
    }
}
