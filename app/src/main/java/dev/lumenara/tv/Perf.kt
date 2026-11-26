package dev.lumenara.tv

import android.os.SystemClock
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/** Main-thread, one-shot transitions. Tokens reject late callbacks, including A -> B -> A. */
internal class PerfTransitions(
    private val clock: () -> Long,
    private val emit: (String) -> Unit,
) {
    class Request(val started: Long, val target: String? = null)
    private var zap: Request? = null
    private var category: Request? = null
    fun zapStart() { zap = Request(clock()); emit("zap_start ms=0") }
    fun zapCancel() { zap = null }
    fun zapSelected(id: String) { zap = Request(zap?.started ?: clock(), id) }
    fun zapRequest(id: String): Request? = zap?.takeIf { it.target == id }
    fun zapPending(request: Request?): Boolean = request != null && zap === request
    fun zapFirstFrame(request: Request?, engine: String) {
        if (request == null || zap !== request) return
        zap = null
        emit("zap_first_frame ms=${clock() - request.started} engine=$engine")
    }
    fun categoryStart() { category = Request(clock()) }
    fun categoryRequest(): Request? = category
    fun categoryCancel(request: Request?) { if (category === request) category = null }
    fun categoryFirstDraw(request: Request?) {
        if (request == null || category !== request) return
        category = null
        emit("category_grid_first_draw ms=${clock() - request.started}")
    }
}

/** Runs after grid content draws, never for loading placeholders; empty results also complete. */
@Composable
internal fun Modifier.categoryFirstDraw(ready: Boolean, transitions: PerfTransitions = Perf.transitions): Modifier {
    val request = transitions.categoryRequest()
    val drawn = remember(request) { booleanArrayOf(false) }
    DisposableEffect(request) { onDispose { transitions.categoryCancel(request) } }
    return drawWithContent {
        drawContent()
        if (ready && !drawn[0]) {
            drawn[0] = true
            transitions.categoryFirstDraw(request)
        }
    }
}

/** Fixed event names only: never pass content titles, IDs or addresses. Enabled in release. */
internal object Perf {
    private val marks = ConcurrentHashMap<String, Long>()
    val transitions = PerfTransitions(SystemClock::elapsedRealtime) { Log.i("LumenaraPerf", it) }
    fun zapStart() = transitions.zapStart()
    fun zapCancel() = transitions.zapCancel()
    fun zapSelected(id: String) = transitions.zapSelected(id)
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
