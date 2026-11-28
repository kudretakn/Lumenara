package dev.lumenara.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class Wp17SettingIdsTest {
    private class MemoryStore : DataStore<StoredPreferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (StoredPreferences) -> StoredPreferences): StoredPreferences =
            mutex.withLock { transform(data.value).toPreferences().also { data.value = it } }

        suspend fun seed(key: String, value: String) {
            updateData { it.toMutablePreferences().apply { this[stringPreferencesKey(key)] = value } }
        }
        fun raw(key: String) = data.value[stringPreferencesKey(key)]
    }

    private fun preferences(store: MemoryStore) = Preferences(store, { "sealed:$it" }, { it.removePrefix("sealed:") })
    private val aliases = mapOf(
        "frame_rate" to listOf("Kapalı" to "off", "Yalnız film ve dizi" to "vod_only", "Her zaman" to "always"),
        "watch_next" to listOf("Açık" to "on", "Kapalı" to "off"),
        "engine" to listOf("Media3" to "media3", "VLC" to "vlc"),
        "fallback" to listOf("Açık" to "on", "Kapalı" to "off"),
        "buffer" to listOf("Dengeli" to "balanced", "Hızlı" to "fast", "Kararlı" to "steady"),
        "audio" to listOf("Otomatik (Türkçe)" to "auto_tr", "tr" to "tr", "en" to "en", "de" to "de", "fr" to "fr"),
        "sub" to listOf("Otomatik (Türkçe)" to "auto_tr", "Kapalı" to "off", "tr" to "tr", "en" to "en", "de" to "de", "fr" to "fr"),
        "size" to listOf("Orta" to "medium", "Büyük" to "large", "Küçük" to "small"),
        "color" to listOf("Beyaz" to "white", "Sarı" to "yellow"),
        "diagnostics" to listOf("Kapalı" to "off", "Açık" to "on"),
    )

    @Test fun everyLegacyValueReadsWithoutMutationAndWritesAnIdUnderTheSameKey() = runBlocking {
        val store = MemoryStore()
        val prefs = preferences(store)
        for ((key, values) in aliases) for ((legacy, id) in values) {
            store.seed(key, legacy) // Bypass the new writer to represent an installed older app.
            assertEquals(legacy, prefs.text(key).first())
            assertEquals(id, prefs.choiceId(key).first())
            assertEquals("Reading must not rewrite installed data", legacy, store.raw(key))
            prefs.set(key, legacy)
            assertEquals(id, store.raw(key))
            assertEquals(legacy, prefs.text(key).first())
            prefs.set(key, id)
            assertEquals(id, store.raw(key))
            assertEquals(legacy, prefs.text(key).first())
        }
        assertEquals(aliases.keys, store.data.value.asMap().keys.map { it.name }.toSet())
    }

    @Test fun v1RestoreBackupAndRollbackPreserveEveryChoiceSemantically() = runBlocking {
        for ((key, values) in aliases) for ((legacy, id) in values) {
            val store = MemoryStore()
            val prefs = preferences(store)
            store.seed(key, legacy)
            val oldSnapshot = prefs.snapshotSettings()
            prefs.set(key, "unknown-future-choice")
            prefs.restoreSnapshot(oldSnapshot)
            assertEquals(id, store.raw(key))
            assertEquals(legacy, prefs.text(key).first())
            prefs.restoreSettings(listOf(BackupSetting(key, "string", legacy)), emptyMap(), emptyMap())
            assertEquals(id, store.raw(key))
            assertEquals(legacy, prefs.text(key).first())
            val backup = prefs.backupSettings()
            assertEquals(listOf(BackupSetting(key, "string", id)), backup)
            val snapshot = prefs.snapshotSettings()
            prefs.set(key, "unknown-future-choice")
            prefs.restoreSnapshot(snapshot)
            assertEquals(legacy, prefs.text(key).first())
            val other = preferences(MemoryStore())
            other.restoreSettings(backup, emptyMap(), emptyMap())
            assertEquals(id, other.choiceId(key).first())
            assertEquals(legacy, other.text(key).first())
        }
    }

    @Test fun unknownValuesUnsetDefaultsAndItemSpecificChoicesAreLossless() = runBlocking {
        val store = MemoryStore()
        val prefs = preferences(store)
        assertEquals("vod_only", prefs.choiceId("frame_rate", "vod_only").first())
        assertEquals("Yalnız film ve dizi", prefs.text("frame_rate", "Yalnız film ve dizi").first())
        assertNull(store.raw("frame_rate"))
        for (key in aliases.keys) {
            prefs.set(key, "future:value")
            assertEquals("future:value", prefs.choiceId(key).first())
            assertEquals("future:value", prefs.text(key).first())
            assertEquals("future:value", store.raw(key))
        }
        for (key in listOf("audio_item", "text_item", "sub_item", "engine_item", "events", "github")) {
            prefs.set(key, "Açık")
            assertEquals("Açık", store.raw(key))
            assertEquals("Açık", prefs.text(key).first())
        }
        prefs.setSecret("tmdb", "Açık")
        assertEquals("sealed:Açık", store.raw("secret_tmdb"))
        assertEquals("Açık", prefs.secret("tmdb"))
    }

    @Test fun playbackKeepsItsGlobalDefaultsAndItemOverridesAfterIdWrites() = runBlocking {
        val prefs = preferences(MemoryStore())
        prefs.set("audio", "auto_tr")
        assertEquals("", prefs.playbackChoice("audio", "item"))
        prefs.set("audio", "en")
        assertEquals("en", prefs.playbackChoice("audio", "item"))
        prefs.set("sub", "off")
        assertEquals("off", prefs.playbackChoice("text", "item"))
        prefs.set("text_item", "downloaded")
        assertEquals("downloaded", prefs.playbackChoice("text", "item"))
    }
}
