package dev.lumenara.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RestoreTransactionTest {
    private class FailingDataStore : DataStore<StoredPreferences> {
        override val data = MutableStateFlow<StoredPreferences>(emptyPreferences())
        var failNextEdit = false
        var edits = 0
        override suspend fun updateData(transform: suspend (StoredPreferences) -> StoredPreferences): StoredPreferences {
            val next = transform(data.value).toPreferences()
            edits++
            if (failNextEdit) {
                failNextEdit = false
                throw IOException("DataStore write failed")
            }
            data.value = next
            return next
        }
    }

    // The injected DAO models Room's commit/rollback boundary, including its journal row.
    private class FailingDao {
        val sources = mutableListOf("old source")
        var journal: String? = null
        var failInsert = false
        var failCommit = false
        var killBeforeCommit = false
        var unavailable = false
        var inTransaction = false
        fun insert(value: String) {
            sources.add(value)
            if (failInsert) throw IOException("DAO insert failed midway")
        }
        suspend fun <T> transaction(block: suspend () -> T): T {
            val oldSources = sources.toList()
            val oldJournal = journal
            try {
                inTransaction = true
                val result = block()
                if (killBeforeCommit) {
                    unavailable = true
                    throw IOException("Process killed before Room commit")
                }
                if (failCommit) throw IOException("Room commit failed")
                return result
            } catch (failure: Throwable) {
                sources.clear()
                sources.addAll(oldSources)
                journal = oldJournal
                throw failure
            } finally { inTransaction = false }
        }
    }

    private class Fixture : RestoreStorage {
        val store = FailingDataStore()
        // Deterministic encryption substitute; tests never depend on Android Keystore.
        val preferences = Preferences(store, { "sealed:$it" }, { it.removePrefix("sealed:") })
        val dao = FailingDao()
        var token = "sealed:old token"
        var failToken = false
        var failJournalSave = false
        var failJournalDelete = false
        override suspend fun pending(): String? {
            if (dao.unavailable) throw IOException("Process is down")
            return dao.journal
        }
        override suspend fun snapshot() = JSONObject().put("settings", preferences.snapshotSettings())
            .put("token", token).toString()
        override suspend fun savePending(snapshot: String) {
            dao.journal = snapshot
            if (failJournalSave) throw IOException("Journal durability barrier failed")
        }
        override suspend fun restoreSnapshot(snapshot: String) {
            val old = JSONObject(snapshot)
            preferences.restoreSnapshot(old.getString("settings"))
            token = old.getString("token")
        }
        override suspend fun clearPending() {
            if (failJournalDelete) {
                failJournalDelete = false
                throw IOException("Journal delete failed")
            }
            dao.journal = null
        }
        override suspend fun <T> transaction(block: suspend () -> T) = dao.transaction(block)
        suspend fun initialize() {
            preferences.setShowAdult(false)
            preferences.setSecret("test", "old secret")
            preferences.set("series_expanded_old", "123")
        }
        suspend fun restore() = RestoreTransaction(this).run {
            dao.sources.clear()
            dao.insert("new source 1")
            dao.insert("new source 2")
            preferences.restoreSettings(listOf(
                BackupSetting("show_adult", "boolean", "true"),
                BackupSetting("secret_test", "string", "new secret"),
            ), emptyMap(), emptyMap())
            token = "sealed:new token"
            if (failToken) throw IOException("Token write failed")
        }
        suspend fun assertOldState() {
            assertEquals(listOf("old source"), dao.sources)
            assertFalse(preferences.showAdult.first())
            assertEquals("old secret", preferences.secret("test"))
            assertEquals("sealed:old secret", preferences.text("secret_test").first())
            assertEquals("123", preferences.text("series_expanded_old").first())
            assertEquals("sealed:old token", token)
            assertNull(dao.journal)
        }
    }

    @Test fun failedJournalDurabilityCheckDoesNotLeaveStaleUndoState() = runTest {
        val f = Fixture().also { it.initialize() }
        f.failJournalSave = true
        assertTrue(runCatching { f.restore() }.exceptionOrNull() is IOException)
        f.assertOldState()
    }

    @Test fun journalDeleteFailureRollsBackPublishedSettingsAndRoom() = runTest {
        val f = Fixture().also { it.initialize() }
        f.failJournalDelete = true
        assertTrue(runCatching { f.restore() }.exceptionOrNull() is IOException)
        f.assertOldState()
    }

    @Test fun failingDaoMidwayLeavesOldDatabaseAndPreferences() = runTest {
        val f = Fixture().also { it.initialize() }
        f.dao.failInsert = true
        assertTrue(runCatching { f.restore() }.exceptionOrNull() is IOException)
        f.assertOldState()
    }

    @Test fun failingDataStoreRollsBackAllRoomWrites() = runTest {
        val f = Fixture().also { it.initialize() }
        f.store.failNextEdit = true
        assertTrue(runCatching { f.restore() }.exceptionOrNull() is IOException)
        f.assertOldState()
    }

    @Test fun failingTokenAfterDataStoreEditRestoresSettingsAndDatabase() = runTest {
        val f = Fixture().also { it.initialize() }
        f.failToken = true
        assertTrue(runCatching { f.restore() }.exceptionOrNull() is IOException)
        f.assertOldState()
    }

    @Test fun failedRoomCommitAfterSettingsPublishRestoresOldState() = runTest {
        val f = Fixture().also { it.initialize() }
        f.dao.failCommit = true
        assertTrue(runCatching { f.restore() }.exceptionOrNull() is IOException)
        f.assertOldState()
    }

    @Test fun restartRecoversKillBetweenSettingsEditAndRoomCommit() = runTest {
        val f = Fixture().also { it.initialize() }
        f.dao.killBeforeCommit = true
        assertTrue(runCatching { f.restore() }.exceptionOrNull() is BackupRecoveryException)
        assertTrue(f.preferences.showAdult.first())
        assertEquals(listOf("old source"), f.dao.sources)
        assertNotNull(f.dao.journal)
        f.dao.unavailable = false
        RestoreTransaction(f).recover()
        f.assertOldState()
        RestoreTransaction(f).recover() // Recovery is safe to repeat.
        f.assertOldState()
    }

    @Test fun startupRecoversKillBeforeAnyDatabaseWrite() = runTest {
        val f = Fixture().also { it.initialize() }
        f.savePending(f.snapshot())
        RestoreTransaction(f).recover()
        f.assertOldState()
    }

    @Test fun recoveryFailureKeepsJournalForNextLaunch() = runTest {
        val f = Fixture().also { it.initialize() }
        f.savePending(f.snapshot())
        f.preferences.setShowAdult(true)
        f.store.failNextEdit = true
        assertTrue(runCatching { RestoreTransaction(f).recover() }.isFailure)
        assertNotNull(f.dao.journal)
        RestoreTransaction(f).recover()
        f.assertOldState()
    }

    @Test fun cancellationAfterSettingsEditRestoresOldState() = runTest {
        val f = Fixture().also { it.initialize() }
        assertTrue(runCatching {
            RestoreTransaction(f).run {
                f.dao.insert("new source")
                f.preferences.setShowAdult(true)
                throw CancellationException("cancel restore")
            }
        }.exceptionOrNull() is CancellationException)
        f.assertOldState()
    }

    @Test fun successPublishesSettingsOnceAndSurvivesRestart() = runTest {
        val f = Fixture().also { it.initialize() }
        val edits = f.store.edits
        f.restore()
        assertEquals(edits + 1, f.store.edits)
        assertEquals(listOf("new source 1", "new source 2"), f.dao.sources)
        assertTrue(f.preferences.showAdult.first())
        assertEquals("sealed:new secret", f.preferences.text("secret_test").first())
        assertEquals("sealed:new token", f.token)
        assertNull(f.dao.journal)
        RestoreTransaction(f).recover()
        assertTrue(f.preferences.showAdult.first())
    }

    @Test fun preparationFinishesBeforeUndoSnapshotAndRoomCommitWithNoCommitNetworkCalls() = runTest {
        val f = Fixture().also { it.initialize() }
        var networkCalls = 0
        var networkCallsInsideCommit = 0
        suspend fun fetch(): String {
            networkCalls++
            if (f.dao.inTransaction) networkCallsInsideCommit++
            assertNull(f.dao.journal)
            f.assertOldState()
            return "prepared catalog"
        }
        RestoreTransaction(f).prepareAndRun(prepare = { listOf(fetch(), fetch()) }, commit = { catalogs ->
            assertEquals(2, networkCalls)
            assertTrue(f.dao.inTransaction)
            assertNotNull(f.dao.journal)
            catalogs.forEach { f.dao.insert(it) }
            f.preferences.setShowAdult(true)
        })
        assertEquals(0, networkCallsInsideCommit)
        assertNull(f.dao.journal)
        assertTrue(f.preferences.showAdult.first())
    }

    @Test fun failedOrCancelledPreparationPublishesNothingAndCreatesNoUndoJournal() = runTest {
        for (failure in listOf(IOException("download failed"), CancellationException("cancel prepare"))) {
            val f = Fixture().also { it.initialize() }
            val edits = f.store.edits
            val result = runCatching {
                RestoreTransaction(f).prepareAndRun<Unit, Unit>(prepare = { throw failure }, commit = {
                    fail("Commit must never start")
                })
            }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(edits, f.store.edits)
            f.assertOldState()
        }
    }


    @Test fun pendingRestoreQueueRollsBackWithRoomAtFailureCancellationAndProcessDeath() = runTest {
        val source = "saved-source"
        val address = "https://provider.invalid/stream"
        val id = dev.lumenara.catalog.contentKey(source, address)
        val pending = BackupData(listOf(BackupSetting("recent_live", "string", id)),
            listOf(BackupSource(source, "Saved", SourceSpec("https://provider.invalid/list", false))),
            emptyList(), listOf(BackupItem(id, source, address, "")),
            listOf(Mark(id, favorite = true)), emptyList())
        for (boundary in listOf("commit-failure", "cancellation", "process-death")) {
            val f = Fixture().also { it.initialize() }
            f.preferences.setSecret("wp12_pending_restore", "[]")
            f.preferences.setSecret("orphan_history", "device-local pool")
            val oldQueue = f.preferences.text(wp12PendingRestoreSetting).first()
            val oldPool = f.preferences.text(orphanHistorySetting).first()
            f.dao.failCommit = boundary == "commit-failure"
            f.dao.killBeforeCommit = boundary == "process-death"
            assertTrue(runCatching {
                RestoreTransaction(f).run {
                    f.dao.insert("new source")
                    f.preferences.setShowAdult(true)
                    f.preferences.saveWp12PendingRestore(pending, mapOf(source to "target"), setOf(id))
                    if (boundary == "cancellation") throw CancellationException("cancel after pending state")
                }
            }.isFailure)
            if (boundary == "process-death") {
                assertNotNull(f.dao.journal)
                f.dao.unavailable = false
                RestoreTransaction(f).recover()
            }
            f.assertOldState()
            assertEquals(oldQueue, f.preferences.text(wp12PendingRestoreSetting).first())
            assertEquals(oldPool, f.preferences.text(orphanHistorySetting).first())
            assertTrue(f.preferences.wp12PendingRestores().isEmpty())
        }
    }

}
