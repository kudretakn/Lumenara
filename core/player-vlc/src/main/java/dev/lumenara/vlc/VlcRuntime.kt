package dev.lumenara.vlc

import android.content.Context
import org.videolan.libvlc.LibVLC

/** Owns one lazily created native runtime; playback sessions must not release it. */
class VlcRuntime<T : Any> internal constructor(factory: () -> T) {
    private val library by lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)

    fun get(): T = library

    companion object {
        private var shared: VlcRuntime<LibVLC>? = null

        /** Process lifetime ownership, retaining only the application context. */
        @Synchronized
        fun get(context: Context): LibVLC {
            val runtime = shared ?: run {
                val application = context.applicationContext
                VlcRuntime {
                    LibVLC(application, arrayListOf("--quiet", "--no-video-title-show", "--avcodec-threads=2"))
                }.also { shared = it }
            }
            return runtime.get()
        }
    }
}
