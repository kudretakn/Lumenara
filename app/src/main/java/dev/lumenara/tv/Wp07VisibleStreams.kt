package dev.lumenara.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Keep one small last snapshot in the model, with no producer while its screen is hidden. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.wp07WhileVisible(visible: StateFlow<Boolean>): Flow<T> =
    visible.flatMapLatest { if (it) this else emptyFlow() }

internal fun <T> Flow<T>.wp07StartupErrors(): Flow<T> = catch { error ->
    if (error is CancellationException) throw error
    // The startup coordinator exposes Error; an eager reader must not crash the process.
}
