package dev.lumenara.tv

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Fixed event names only: never pass content titles, IDs or addresses. Enabled in release. */
internal object Perf {
    private val marks = ConcurrentHashMap<String, Long>()
    fun mark(name: String) { marks[name] = SystemClock.elapsedRealtime() }
    fun since(name: String, label: String) {
        val start = marks.remove(name) ?: return
        Log.i("LumenaraPerf", "$label ms=${SystemClock.elapsedRealtime() - start}")
    }
}
