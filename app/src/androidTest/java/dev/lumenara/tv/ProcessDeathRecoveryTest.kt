package dev.lumenara.tv

import org.junit.Test

/** Real SIGKILL/Binder death with Room WAL, DataStore and AndroidKeyStore, never an exception fake. */
class ProcessDeathRecoveryTest {
    @Test fun restoreIsAtomicAtEveryDurabilityBoundary() = matrix("restore")
    @Test fun sourceRemovalPreservesRecoverableUserStateAtEveryBoundary() = matrix("delete")
    @Test fun sourceReadditionPreservesRecoverableUserStateAtEveryBoundary() = matrix("add")

    private fun matrix(operation: String) {
        wp11Server().use { server ->
            val endpoint = server.url("/list?fixture=wp11-private").toString()
            for (boundary in listOf("before_undo", "after_undo", "after_checkpoint", "after_settings", "before_commit", "after_commit")) {
                Wp11RecoveryClient().use { client ->
                    client.command("seed", operation, endpoint, expected = "seeded")
                    client.command("mutate", operation, endpoint, boundary, expected = "paused")
                    client.killAndAwaitDeath()
                }
                Wp11RecoveryClient().use { reopened ->
                    reopened.command("verify", operation, endpoint, committed = boundary == "after_commit", expected = "verified")
                    reopened.killAndAwaitDeath()
                }
                // Recovery is idempotent; the first restart must not consume or duplicate user state.
                Wp11RecoveryClient().use { again ->
                    again.command("upgrade", committed = operation == "restore" && boundary == "after_commit", expected = "verified")
                }
            }
        }
    }
}
