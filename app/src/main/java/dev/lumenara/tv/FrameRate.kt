package dev.lumenara.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.lumenara.design.LumenColors
import dev.lumenara.catalog.*
import kotlinx.coroutines.delay

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

// The first SurfaceView is the video output; VLC may add a second one for subtitles.
private fun View.videoSurface(): SurfaceView? {
    if (this is SurfaceView) return this
    if (this is ViewGroup) for (index in 0 until childCount) {
        getChildAt(index).videoSurface()?.let { return it }
    }
    return null
}

@Composable
internal fun FrameRateMatch(model: LibraryModel, kind: String, frameRate: Float?, output: View?, active: Boolean = true) {
    val setting by remember { model.prefs.text("frame_rate", FRAME_RATE_DEFAULT) }
        .collectAsStateWithLifecycle(null)
    val window = LocalContext.current.activity()?.window ?: return
    var adjusting by remember { mutableStateOf(false) }
    val enabled = active && setting?.let { shouldMatchFrameRate(it, kind) } == true
    DisposableEffect(window, output, frameRate, enabled) {
        val original = window.attributes.preferredDisplayModeId
        val surfaces = mutableSetOf<Surface>()
        var watched: SurfaceHolder? = null
        var callback: SurfaceHolder.Callback? = null
        var changed = false
        var observer: android.view.ViewTreeObserver? = null
        var listener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
        fun apply() {
            val fps = frameRate ?: return
            if (!enabled || !fps.isFinite() || fps <= 0f) return
            val video = output?.videoSurface() ?: return
            if (watched !== video.holder) {
                callback?.let { watched?.removeCallback(it) }
                watched = video.holder
                callback = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { apply() }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { apply() }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaces.remove(holder.surface) }
                }
                callback?.let { video.holder.addCallback(it) }
            }
            val surface = video.holder.surface
            if (!surface.isValid || surface in surfaces) return
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    if (Build.VERSION.SDK_INT >= 31) surface.setFrameRate(
                        fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
                    else surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                    surfaces.add(surface)
                    adjusting = true
                } else {
                    val display = video.display ?: return
                    fun android.view.Display.Mode.value() = FrameRateMode(modeId, physicalWidth, physicalHeight, refreshRate)
                    val selected = matchFrameRate(fps, display.mode.value(), display.supportedModes.map { it.value() }) ?: return
                    surfaces.add(surface)
                    if (selected.id != display.mode.modeId && selected.id != window.attributes.preferredDisplayModeId) {
                        window.attributes = window.attributes.apply { preferredDisplayModeId = selected.id }
                        changed = true
                        adjusting = true
                    }
                }
            } catch (_: IllegalArgumentException) {
                // Some TV firmware rejects otherwise valid surface hints.
            } catch (_: IllegalStateException) {
                // Native output can disappear while the player is being replaced.
            }
        }
        if (enabled) {
            apply()
            listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { apply() }
            observer = output?.viewTreeObserver
            listener?.let { observer?.addOnGlobalLayoutListener(it) }
        }
        onDispose {
            callback?.let { watched?.removeCallback(it) }
            listener?.let { callback -> observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(callback) }
            if (Build.VERSION.SDK_INT >= 30) surfaces.forEach { surface ->
                if (surface.isValid) runCatching { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
            }
            if (changed) window.attributes = window.attributes.apply { preferredDisplayModeId = original }
            adjusting = false
        }
    }
    LaunchedEffect(adjusting) {
        if (adjusting) { delay(2000); adjusting = false }
    }
    if (adjusting) Box(Modifier.padding(24.dp).background(LumenColors.surface1).padding(12.dp).testTag("frame-rate-adjusting")) {
        Text("Kare hızı ayarlanıyor")
    }
}
