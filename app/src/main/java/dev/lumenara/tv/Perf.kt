package dev.lumenara.tv

import android.os.SystemClock
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.ConcurrentHashMap

/** Fixed event names only: never pass content titles, IDs or addresses. Enabled in release. */
internal object Perf {
    private val marks = ConcurrentHashMap<String, Long>()
    fun duration(name: String, millis: Long) { Log.i("LumenaraPerf", "$name ms=$millis") }
    fun mark(name: String) { marks[name] = SystemClock.elapsedRealtime() }
    fun elapsed(name: String, label: String) {
        val start = marks[name] ?: return
        Log.i("LumenaraPerf", "$label ms=${SystemClock.elapsedRealtime() - start}")
    }
    fun since(name: String, label: String) {
        val start = marks.remove(name) ?: return
        Log.i("LumenaraPerf", "$label ms=${SystemClock.elapsedRealtime() - start}")
    }

    /** Debug-only UI frame timings; one summary per foreground session, no per-frame logs. */
    fun observeFrames(window: Window): () -> Unit {
        if (!BuildConfig.DEBUG) return {}
        val refreshRate = window.decorView.display?.refreshRate?.takeIf { it > 0f } ?: 60f
        val budgetNs = (1_000_000_000L / refreshRate).toLong()
        var frames = 0L
        var overBudget = 0L
        var totalNs = 0L
        var maxNs = 0L
        var droppedReports = 0L
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            droppedReports += dropped
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (duration > 0) {
                    frames++
                    totalNs += duration
                    maxNs = maxOf(maxNs, duration)
                    if (duration > budgetNs) overBudget++
                }
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        return {
            window.removeOnFrameMetricsAvailableListener(listener)
            if (frames > 0) Log.i("LumenaraPerf",
                "ui_frames count=$frames avg_ms=${totalNs / frames / 1_000_000.0} " +
                    "max_ms=${maxNs / 1_000_000.0} over_budget_pct=${100.0 * overBudget / frames} " +
                    "budget_ms=${budgetNs / 1_000_000.0} dropped_reports=$droppedReports")
        }
    }
}
