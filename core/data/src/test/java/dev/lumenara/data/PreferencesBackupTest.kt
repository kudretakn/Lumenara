package dev.lumenara.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreferencesBackupTest {
    private class MemoryStore : DataStore<StoredPreferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (StoredPreferences) -> StoredPreferences): StoredPreferences =
            mutex.withLock { transform(data.value).toPreferences().also { data.value = it } }
    }

    private fun preferences() = Preferences(MemoryStore(), { "sealed:$it" }, { it.removePrefix("sealed:") })

    @Test fun updateStateIsExcludedFromBackupAndIncomingRestore() = runBlocking {
        val prefs = preferences()
        prefs.set("theme", "dark")
        prefs.beginUpdateCheck("o/r", automatic = true, now = 1234)
        prefs.setUpdateRelease("o/r", "v2", "etag", "cached release")
        val localState = prefs.updateState("o/r")
        val incoming = listOf("update_o/r", "update_checkedAt", "update_ETag", "update_cached_release")
            .map { BackupSetting(it, "string", "foreign state") }
        incoming.drop(1).forEach { prefs.set(it.name, "local state") }
        assertEquals(listOf(BackupSetting("theme", "string", "dark")), prefs.backupSettings())

        prefs.set("added_after_backup", "remove me")
        prefs.restoreSettings(incoming + BackupSetting("theme", "string", "light"), emptyMap(), emptyMap())
        assertEquals(localState, prefs.updateState("o/r"))
        incoming.drop(1).forEach { assertEquals("local state", prefs.text(it.name).first()) }
        assertEquals("", prefs.text("added_after_backup").first())
        assertEquals(listOf(BackupSetting("theme", "string", "light")), prefs.backupSettings())

        val clean = preferences()
        clean.restoreSettings(incoming, emptyMap(), emptyMap())
        incoming.forEach { assertEquals("", clean.text(it.name).first()) }
    }

    @Test fun rollbackSnapshotsNeverRewindUpdateStateEvenFromOlderJournals() = runBlocking {
        val prefs = preferences()
        prefs.set("theme", "dark")
        prefs.beginUpdateCheck("o/r", automatic = true, now = 1234)
        val snapshot = prefs.snapshotSettings()
        assertFalse(snapshot.contains("update_"))
        prefs.beginUpdateCheck("o/r", automatic = false, now = 5678)
        prefs.setUpdateRelease("o/r", "v2", "etag", "release")
        val latest = prefs.updateState("o/r")
        prefs.set("theme", "light")
        val legacy = JSONArray(snapshot).put(JSONObject().put("name", "update_o/r")
            .put("type", "string").put("value", "stale state"))
        prefs.restoreSnapshot(legacy.toString())
        assertEquals("dark", prefs.text("theme").first())
        assertEquals(latest, prefs.updateState("o/r"))
    }
}
