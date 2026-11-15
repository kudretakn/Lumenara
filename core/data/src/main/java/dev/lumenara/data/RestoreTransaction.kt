package dev.lumenara.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class BackupRecoveryException internal constructor(cause: Throwable) : IllegalStateException(
    "Geri yükleme tamamlanamadı. Önceki durumu kurtarmak için uygulamayı yeniden başlatın.", cause,
)

internal interface RestoreStorage {
    suspend fun pending(): String?
    suspend fun snapshot(): String
    suspend fun savePending(snapshot: String)
    suspend fun restoreSnapshot(snapshot: String)
    suspend fun clearPending()
    suspend fun <T> transaction(block: suspend () -> T): T
}

/** Called under the repository lock, and during construction before exposing any state. */
internal class RestoreTransaction(private val storage: RestoreStorage) {
    suspend fun recover() {
        val old = storage.pending() ?: return
        storage.restoreSnapshot(old)
        storage.clearPending()
    }

    suspend fun <T> run(block: suspend () -> T): T {
        recover()
        // The journal is durable BEFORE any restore writes. Its payload is encrypted by Vault.
        // Deleting it IN the Room transaction is the commit decision: a crash/failed commit
        // rolls back Room and preserves the old settings snapshot. Startup replays that snapshot
        // before exposing state. A successful commit removes the journal atomically, so recovery
        // keeps the new state. Merely editing DataStore inside withTransaction cannot close the
        // process-kill window between the two stores' commits. Recovery itself is idempotent.
        val old = storage.snapshot()
        return try {
            storage.savePending(old)
            storage.transaction {
                val result = block()
                storage.clearPending()
                result
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { recover() }
                catch (recovery: Throwable) {
                    failure.addSuppressed(recovery)
                    throw BackupRecoveryException(failure)
                }
            }
            throw failure
        }
    }
}
