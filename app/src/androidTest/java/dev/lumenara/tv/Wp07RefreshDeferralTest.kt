package dev.lumenara.tv

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.lumenara.data.Preferences
import dev.lumenara.data.RefreshGate
import dev.lumenara.data.RefreshWorker
import dev.lumenara.data.Repository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Real WorkManager persistence/worker execution, sharing the optimized smoke's WP07 contract. */
class Wp07RefreshDeferralTest {
    @Test fun foregroundAndPlaybackRetainOneImmediateRequestWithDurableRetryBackoff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = runBlocking { Repository.awaitReady(context) }
        val prefs = Preferences(context)
        val manager = WorkManager.getInstance(context)
        val database = File(context.noBackupFilesDir, "androidx.work.workdb")
        val oldForeground = RefreshGate.foreground
        val oldPlaying = RefreshGate.playing
        // Do not consume or cancel another test's outstanding intent.
        assertEquals("Requires no outstanding refresh intent", "false",
            runBlocking { prefs.text("wp07_pending_refresh", "false").first() })
        assertTrue("Requires no active deferred refresh", manager.getWorkInfosForUniqueWork("deferred-refresh")
            .get(10, TimeUnit.SECONDS).none { !it.state.isFinished })
        val requests = mutableListOf<java.util.UUID>()
        try {
            for (playback in listOf(false, true)) {
                RefreshGate.playing = playback
                RefreshGate.foreground = !playback
                val scheduledAfter = System.currentTimeMillis()
                fun runOccurrence() {
                    val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                        .setInputData(workDataOf("staleHours" to 12L)).build()
                    requests += request.id
                    manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
                    await("startup-style occurrence success") {
                        manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)?.state ==
                            WorkInfo.State.SUCCEEDED
                    }
                }
                runOccurrence()
                val pending = awaitPendingRetry(database)
                assertContract(pending, scheduledAfter)
                assertEquals("true", runBlocking { prefs.text("wp07_pending_refresh").first() })

                // Further busy occurrences and process/background replay must KEEP the same
                // WorkSpec, rather than replacing its backoff or appending another request.
                repeat(3) {
                    runOccurrence()
                    runBlocking { repo.retryPendingRefresh() }
                    val retained = awaitPendingRetry(database)
                    assertEquals(pending.id, retained.id)
                    assertContract(retained, scheduledAfter)
                }
                manager.cancelUniqueWork("deferred-refresh").result.get(10, TimeUnit.SECONDS)
                runBlocking { prefs.set("wp07_pending_refresh", "false") }
            }
        } finally {
            // Cancel while still busy, before restoring the gate, so fixtures never refresh.
            try {
                requests.forEach { manager.cancelWorkById(it).result.get(10, TimeUnit.SECONDS) }
                manager.cancelUniqueWork("deferred-refresh").result.get(10, TimeUnit.SECONDS)
                runBlocking { prefs.set("wp07_pending_refresh", "false") }
            } finally {
                RefreshGate.foreground = oldForeground
                RefreshGate.playing = oldPlaying
            }
        }
    }

    private fun assertContract(row: PendingRow, scheduledAfter: Long) {
        assertEquals("dev.lumenara.data.RefreshWorker", row.worker)
        assertEquals(0, row.state) // ENQUEUED again after Result.retry(), not merely created.
        assertEquals(0L, row.initialDelay)
        assertEquals(0L, row.interval)
        assertEquals(mapOf("wp07Pending" to true), row.input.keyValueMap)
        assertTrue(row.attempts > 0)
        assertEquals(1, row.backoffPolicy) // LINEAR
        assertEquals(30_000L, row.backoffDelay)
        assertTrue(row.enqueued >= scheduledAfter)
    }

    private data class PendingRow(
        val id: String, val worker: String, val state: Int, val input: Data,
        val initialDelay: Long, val interval: Long, val attempts: Int,
        val backoffPolicy: Int, val backoffDelay: Long, val enqueued: Long,
    )

    private fun awaitPendingRetry(database: File): PendingRow {
        var result: PendingRow? = null
        await("exactly one persisted pending-worker retry") {
            result = pendingRows(database).singleOrNull()?.takeIf { it.state == 0 && it.attempts > 0 }
            result != null
        }
        return checkNotNull(result)
    }

    private fun pendingRows(file: File): List<PendingRow> {
        if (!file.exists()) return emptyList()
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""SELECT w.id, w.worker_class_name, w.state, w.input, w.initial_delay,
                w.interval_duration, w.run_attempt_count, w.backoff_policy,
                w.backoff_delay_duration, w.last_enqueue_time
                FROM WorkSpec w JOIN WorkName n ON n.work_spec_id = w.id
                WHERE n.name = 'deferred-refresh' AND w.state IN (0, 1, 4)""", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(PendingRow(
                        cursor.getString(0), cursor.getString(1), cursor.getInt(2),
                        Data.fromByteArray(cursor.getBlob(3)), cursor.getLong(4), cursor.getLong(5),
                        cursor.getInt(6), cursor.getInt(7), cursor.getLong(8), cursor.getLong(9)))
                }
            }
        }
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $label")
    }
}
