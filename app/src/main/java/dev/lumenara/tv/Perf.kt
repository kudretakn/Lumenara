package dev.lumenara.tv

import android.os.SystemClock
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import android.view.Choreographer
import android.os.Process
import android.os.Trace
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
    // Re-observing a selection during composition/retry/fallback keeps its token.
    fun zapSelected(id: String) {
        if (zap?.target != id) zap = Request(zap?.started ?: clock(), id)
    }
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
    // Updated by the public frame callback before traversal, on the same main thread as draw.
    private var frameVsyncNanos = 0L
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

    /** Draw timestamps correlate with external gfxinfo/atrace; they are not presentation times. */
    fun hubDraw(label: String) {
        val uptime = SystemClock.uptimeMillis()
        Trace.beginSection(label)
        Log.i("LumenaraPerf", "$label process_ms=${uptime - Process.getStartUptimeMillis()} " +
            "uptime_ms=$uptime draw_ns=${System.nanoTime()} " +
            "frame_vsync_ns=$frameVsyncNanos")
        Trace.endSection()
    }

    /** Release too, including FIRST_DRAW_FRAME. One bounded summary, no per-frame log traffic. */
    fun observeFrames(window: Window): () -> Unit {
        val choreographer = Choreographer.getInstance()
        frameVsyncNanos = 0L
        val frameClock = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameVsyncNanos = frameTimeNanos
                choreographer.postFrameCallback(this)
            }
        }
        val refreshRate = window.decorView.display?.refreshRate?.takeIf { it > 0f } ?: 60f
        val budgetNs = (1_000_000_000L / refreshRate).toLong()
        val stats = Wp05FrameStats(budgetNs)
        var droppedReports = 0L
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            droppedReports += dropped
            stats.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 0L)
        }
        window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        choreographer.postFrameCallback(frameClock)
        return {
            choreographer.removeFrameCallback(frameClock)
            frameVsyncNanos = 0L
            window.removeOnFrameMetricsAvailableListener(listener)
            if (stats.count > 0) Log.i("LumenaraPerf",
                "ui_frames count=${stats.count} avg_ms=${stats.totalNs / stats.count / 1_000_000.0} " +
                    "max_ms=${stats.maxNs / 1_000_000.0} over_budget_pct=${100.0 * stats.overBudget / stats.count} " +
                    "p50_upper_ms=${stats.percentileUpperMs(50)} p95_upper_ms=${stats.percentileUpperMs(95)} " +
                    "percentile_cap_ms=10000 over_32ms=${stats.over32} over_50ms=${stats.over50} " +
                    "first_draw_count=${stats.firstDrawCount} first_draw_max_ms=${stats.firstDrawMaxNs / 1_000_000.0} " +
                    "budget_ms=${budgetNs / 1_000_000.0} dropped_reports=$droppedReports")
        }
    }
}
