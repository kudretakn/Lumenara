package dev.lumenara.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Includes query/commit time when tuning the next small transaction. No catalog-wide work. */
internal class Wp07BatchBudget {
    var limit: Int = 16
        private set
    fun record(elapsedMillis: Long) {
        limit = when {
            elapsedMillis >= 25 -> (limit / 2).coerceAtLeast(1)
            elapsedMillis < 10 -> (limit + 1).coerceAtMost(32)
            else -> limit
        }
    }
}

internal class Wp07MaintenanceRunner(
    private val queue: BackgroundWorkQueue,
    private val userWaiting: () -> Boolean,
    private val clockMillis: () -> Long,
    private val batch: suspend (Int) -> Boolean,
) {
    suspend fun run() {
        val budget = Wp07BatchBudget()
        while (currentCoroutineContext().isActive) {
            val more = queue.whenMaintenanceIdle {
                if (userWaiting()) true else {
                    val start = clockMillis()
                    batch(budget.limit).also { budget.record(clockMillis() - start) }
                }
            }
            if (!more) return
            // Every transaction returns to the gate, including after the startup deadline.
            delay(50)
        }
    }
}
