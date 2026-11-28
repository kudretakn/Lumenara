package dev.lumenara.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Sharing whose inactive entries can be evicted without leaving process-scope sharing jobs.
 * An evicted flow still works for an existing screen; its last subscriber releases its producer.
 */
internal fun <T : Any> Flow<T>.wp07Observed(scope: CoroutineScope): Flow<T> {
    val upstream = this
    val lock = Any()
    val snapshot = MutableStateFlow<T?>(null)
    var readers = 0
    var producer: Job? = null
    var stopping: Job? = null
    var generation = 0L
    return flow {
        synchronized(lock) {
            stopping?.cancel()
            stopping = null
            readers++
            if (producer == null) {
                val current = ++generation
                producer = scope.launch {
                    upstream.collect { value ->
                        synchronized(lock) { if (generation == current) snapshot.value = value }
                    }
                }
            }
        }
        try { emitAll(snapshot.filterNotNull()) }
        finally {
            synchronized(lock) {
                readers--
                if (readers == 0) stopping = scope.launch {
                    delay(500)
                    synchronized(lock) {
                        if (readers == 0) {
                            generation++
                            producer?.cancel()
                            producer = null
                            snapshot.value = null
                            stopping = null
                        }
                    }
                }
            }
        }
    }
}
