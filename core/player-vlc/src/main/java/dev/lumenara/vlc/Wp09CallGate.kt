package dev.lumenara.vlc

import java.io.IOException

/** Linearizes cancellation with Call creation/publication, including later FUSE seeks. */
internal class Wp09CallGate<T>(private val cancelCall: (T) -> Unit) {
    private var cancelled = false
    private var current: T? = null

    @Synchronized fun create(factory: () -> T): T {
        checkActive()
        return factory().also { current = it }
    }

    @Synchronized fun checkActive() {
        if (cancelled) throw IOException("VLC input cancelled")
    }

    @Synchronized fun cancelCurrent() { current?.let(cancelCall) }

    @Synchronized fun cancel() {
        if (cancelled) return
        cancelled = true
        current?.let(cancelCall)
    }
}
