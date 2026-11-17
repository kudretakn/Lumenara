package dev.lumenara.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import dev.lumenara.vlc.VlcRuntime

/** Defer all native work until a populated Hub frame has reached the screen. */
@Composable
internal fun WarmVlcAfterHubFrame(ready: Boolean) {
    val application = LocalContext.current.applicationContext
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        VlcRuntime.warmUp(application)
    }
}
