package dev.lumenara.data

import android.os.SystemClock
import android.util.Log

/** Call sites use fixed phase names only, never titles, IDs, credentials or addresses. */
internal object StartupPerf {
    fun duration(event: String, millis: Long) {
        Log.i("LumenaraPerf", "$event ms=$millis")
    }
    fun elapsed(event: String, start: Long) {
        Log.i("LumenaraPerf", "$event ms=${SystemClock.elapsedRealtime() - start}")
    }

    inline fun <T> measure(event: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return try { block() } finally {
            elapsed(event, start)
        }
    }
}
