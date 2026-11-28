package dev.lumenara.tv

import androidx.compose.runtime.*
import dev.lumenara.catalog.SleepTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** No clock is published outside the component that actually displays it. */
@Composable
internal fun rememberWp14VisibleClock(visible: Boolean = true): State<Long> {
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(visible) {
        if (visible) wallClockTicks(1000).collect { clock.longValue = it }
    }
    return clock
}

/** Sleep until the visible final minute; OFF and episode-end timers schedule nothing. */
internal suspend fun wp14SleepTimerWake(timer: SleepTimer, changed: () -> Unit, expired: () -> Unit) {
    withContext(Dispatchers.Main.immediate) {
        val remaining = timer.remainingMs() ?: return@withContext
        delay(if (remaining > 60_000) remaining - 60_000 else remaining.coerceIn(1, 1000))
        val finished = timer.poll()
        changed()
        if (finished) expired()
    }
}

/** A deadline is a single cancellable wake, with no periodic Compose state writes. */
internal suspend fun wp14AwaitDeadline(deadline: Long, now: () -> Long = System::currentTimeMillis,
    expired: () -> Unit) {
    withContext(Dispatchers.Main.immediate) {
        delay((deadline - now()).coerceAtLeast(0))
        expired()
    }
}
