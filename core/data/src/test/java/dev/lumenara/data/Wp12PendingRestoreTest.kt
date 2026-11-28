package dev.lumenara.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.lumenara.catalog.contentKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class Wp12PendingRestoreTest {
    private class MemoryStore : DataStore<StoredPreferences> {
        override val data = MutableStateFlow<StoredPreferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (StoredPreferences) -> StoredPreferences): StoredPreferences =
            transform(data.value).toPreferences().also { data.value = it }
    }
    // Exercise authenticated encryption without Android Keystore; production uses the existing Vault.
    private val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
    private fun seal(value: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key)
        Base64.getEncoder().encodeToString(iv + doFinal(value.toByteArray()))
    }
    private fun open(value: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        val bytes = Base64.getDecoder().decode(value)
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    private fun preferences() = Preferences(MemoryStore(), ::seal, ::open)
    private val address = "https://private.invalid/live/user/secret/17.ts"
    private val item = BackupItem(contentKey("old", address), "old", address, "", "17", kind = "LIVE")
    private val data = BackupData(listOf(BackupSetting("recent_live", "string", item.id),
        BackupSetting("subtitle_delay_old|${item.id}", "string", "750"),
        BackupSetting("version_old|LIVE|channel", "string", "id:${item.id}"),
        BackupSetting("show_adult", "boolean", "false")),
        listOf(BackupSource("old", "Saved", SourceSpec("https://private.invalid", false, "user", "secret"))),
        emptyList(), listOf(item), listOf(Mark(item.id, favorite = true, position = 500, updated = 10)), emptyList())

    @Test fun unresolvedStateIsEncryptedAndOldRestoreCannotEraseEitherRecoveryPool() = runTest {
        val prefs = preferences()
        prefs.setSecret("orphan_history", "local deletion pool")
        val local = prefs.text(orphanHistorySetting).first()
        prefs.saveWp12PendingRestore(data, mapOf("old" to "new"), setOf(item.id))
        val raw = prefs.text(wp12PendingRestoreSetting).first()
        assertFalse(raw.contains("private.invalid"))
        assertFalse(raw.contains("secret"))
        assertEquals(item.id, prefs.wp12PendingRestores().single().data.marks.single().id)
        prefs.restoreSettings(listOf(BackupSetting("show_adult", "boolean", "true")), emptyMap(), emptyMap())
        assertEquals(local, prefs.text(orphanHistorySetting).first())
        assertEquals(setOf(item.id), prefs.wp12PendingRestores().single().ids)
        assertTrue(prefs.showAdult.first())
    }

    @Test fun pendingRestoreTravelsInsideEncryptedV1BackupAndRemapsOnlyTargetSources() = runTest {
        val prefs = preferences()
        prefs.saveWp12PendingRestore(data, mapOf("old" to "new"), setOf(item.id))
        val backup = data.copy(settings = prefs.backupSettings())
        val bytes = BackupCodec.encrypt(backup, "password".toCharArray())
        assertFalse(bytes.toString(Charsets.UTF_8).contains("private.invalid"))
        val restored = preferences()
        restored.restoreSettings(BackupCodec.decrypt(bytes, "password".toCharArray()).settings,
            mapOf("new" to "newer"), emptyMap())
        val pending = restored.wp12PendingRestores().single()
        assertEquals(mapOf("old" to "newer"), pending.sources)
        assertEquals(data, pending.data)
    }

    @Test fun undoSnapshotRestoresPendingQueueAndCiphertextExactly() = runTest {
        val prefs = preferences()
        prefs.saveWp12PendingRestore(data, mapOf("old" to "new"), setOf(item.id))
        val oldCiphertext = prefs.text(wp12PendingRestoreSetting).first()
        val snapshot = prefs.snapshotSettings()
        prefs.saveWp12PendingRestores(emptyList())
        prefs.restoreSnapshot(snapshot)
        assertEquals(oldCiphertext, prefs.text(wp12PendingRestoreSetting).first())
        assertEquals(setOf(item.id), prefs.wp12PendingRestores().single().ids)
    }

    @Test fun retryRestoresItemReferencesWithoutReplayingGlobalsOrReplacingNewerChoices() = runTest {
        val prefs = preferences()
        prefs.setShowAdult(true)
        prefs.recordLive("local-recent")
        prefs.set("subtitle_delay_new|target", "1000")
        prefs.restoreWp12ItemSettings(data.settings, mapOf("old" to "new"), mapOf(item.id to "target"))
        assertTrue(prefs.showAdult.first())
        assertEquals(listOf("local-recent", "target"), prefs.recentLive.first())
        assertEquals("1000", prefs.text("subtitle_delay_new|target").first())
        assertEquals("id:target", prefs.text("version_new|LIVE|channel").first())
    }

    @Test fun queueKeepsAllAmbiguityPeersAndReadsOlderOptionalItemFields() {
        val peer = item.copy(id = contentKey("old", "$address?rendition=2"), address = "$address?rendition=2")
        val pending = Wp12PendingRestore(data.copy(items = listOf(item, peer)), mapOf("old" to "new"), setOf(item.id))
        val wire = JSONArray(encodeWp12PendingRestores(listOf(pending)))
        val items = wire.getJSONObject(0).getJSONObject("data").getJSONArray("items")
        for (i in 0 until items.length()) {
            items.getJSONObject(i).remove("kind")
            items.getJSONObject(i).remove("parentProviderId")
        }
        val decoded = decodeWp12PendingRestores(wire.toString()).single()
        assertEquals(setOf(item.id), decoded.ids)
        assertEquals(2, decoded.data.items.size)
        assertTrue(decoded.data.items.all { it.kind.isEmpty() })
    }
}
