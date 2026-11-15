package dev.lumenara.tv

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.lumenara.design.Panel
import kotlinx.coroutines.flow.collect

@Composable
internal fun BoxScope.DoubleBackExit(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
    }
    var deadline by remember { mutableLongStateOf(0L) }
    LaunchedEffect(enabled, deadline) {
        if (!enabled) deadline = 0
        else if (deadline > 0) wallClockTicks(100).collect {
            if (it >= deadline) deadline = 0
        }
    }
    BackHandler(enabled) {
        val now = System.currentTimeMillis()
        if (deadline > now) {
            deadline = 0
            // Playback is unmounted before reaching Hub; Activity destruction also disposes
            // every remaining Compose-owned Media3/VLC resource.
            activity?.finishAndRemoveTask()
        } else deadline = now + 2_000
    }
    if (enabled && deadline > 0) {
        Panel(Modifier.align(Alignment.BottomCenter).padding(bottom = 27.dp)
            .testTag("exit-confirmation")) {
            Text("Çıkmak için tekrar geri'ye basın", Modifier.padding(16.dp))
        }
    }
}
