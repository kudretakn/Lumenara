package dev.lumenara.vlc

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.videolan.libvlc.LibVLC

/** Owns one lazily created native runtime; playback sessions must not release it. */
class VlcRuntime<T : Any> internal constructor(factory: () -> T) {
    private val library = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)
    private val warming = AtomicBoolean(false)

    fun get(): T = library.value

    internal fun warmUp(executor: Executor) {
        if (library.isInitialized() || !warming.compareAndSet(false, true)) return
        try {
            executor.execute {
                try { get() } finally { warming.set(false) }
            }
        } catch (e: Exception) {
            warming.set(false)
            throw e
        }
    }

    companion object {
        private var shared: VlcRuntime<LibVLC>? = null
        private val warmupExecutor = Executor { task ->
            thread(name = "lumenara-vlc-init", isDaemon = true) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try { task.run() } catch (e: Exception) {
                    Log.w("LumenaraVlc", "runtime warmup failed: ${e.javaClass.simpleName}")
                }
            }
        }

        /** Only the holder is protected here; native initialization never holds this monitor. */
        @Synchronized
        private fun runtime(context: Context): VlcRuntime<LibVLC> = shared ?: run {
            val application = context.applicationContext
            VlcRuntime {
                val began = SystemClock.elapsedRealtime()
                Log.d("LumenaraVlc", "runtime init start")
                LibVLC(application, arrayListOf("--quiet", "--no-video-title-show", "--avcodec-threads=2"))
                    .also { Log.d("LumenaraVlc", "runtime init ready ms=${SystemClock.elapsedRealtime() - began}") }
            }.also { shared = it }
        }

        /** Call after the Hub has drawn. No network or player is opened by warm-up. */
        fun warmUp(context: Context) {
            runtime(context).warmUp(warmupExecutor)
        }

        /** Process lifetime ownership. Playback may wait for warm-up, always off Main. */
        fun get(context: Context): LibVLC {
            check(Looper.myLooper() != Looper.getMainLooper()) { "LibVLC must be acquired off Main" }
            return runtime(context).get()
        }
    }
}
