package dev.lumenara.data

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Call sites use fixed phase names only, never titles, IDs, credentials or addresses. */
internal object StartupPerf {
    fun completed(event: String, millis: Long, total: Long) {
        Log.i("LumenaraPerf", "$event ms=$millis total=$total")
    }
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

/** Measures subscription through the first real result, including Room's query queue. */
internal fun <T> Flow<T>.traceFirstResult(event: String): Flow<T> = flow {
    val start = SystemClock.elapsedRealtime()
    var first = true
    this@traceFirstResult.collect {
        if (first) {
            first = false
            StartupPerf.elapsed(event, start)
        }
        emit(it)
    }
}
