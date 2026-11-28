package dev.lumenara.data

import kotlinx.coroutines.sync.Mutex

/** Persist before enqueue. A process death/enqueue failure leaves exactly one bit to replay.
 * Workers serialize refresh/acknowledgement; completion never acknowledges a failed source.
 */
internal class Wp07PendingRefresh(
    private val read: suspend () -> Boolean,
    private val write: suspend (Boolean) -> Unit,
    private val enqueueUnique: suspend (String) -> Unit,
) {
    suspend fun isPending(): Boolean = read()
    suspend fun request() { write(true); enqueueUnique(wp07PendingWorkName) }
    suspend fun retryIfPending() { if (read()) enqueueUnique(wp07PendingWorkName) }
    suspend fun completed() { write(false) }
}

internal val wp07RefreshWorkerLock = Mutex()
internal const val wp07PendingRefreshKey = "wp07_pending_refresh"
internal const val wp07PendingWorker = "wp07Pending"
internal const val wp07PendingWorkName = "deferred-refresh"
