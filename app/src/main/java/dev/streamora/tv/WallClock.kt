package dev.streamora.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * UI timers follow elapsed real time, independently of the Compose frame/test clock.
 * Only the producer runs in the background; collectors update Compose state on the UI thread.
 * Cancelling the collector (leaving the screen or restarting the hero delay) cancels the timer.
 */
internal fun wallClockTicks(periodMillis: Long): Flow<Long> = flow {
    emit(System.currentTimeMillis())
    while (currentCoroutineContext().isActive) {
        delay(periodMillis)
        emit(System.currentTimeMillis())
    }
}.flowOn(Dispatchers.Default)
