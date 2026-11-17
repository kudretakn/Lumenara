package dev.lumenara.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubtitleDelayTest {
    private class MemoryStore : DataStore<StoredPreferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (StoredPreferences) -> StoredPreferences): StoredPreferences =
            transform(data.value).toPreferences().also { data.value = it }
    }

    private fun item(id: String, source: String = "provider") =
        Item(id, source, "Film", "", "MOVIE", "", "sealed", "", System.currentTimeMillis(), "")

    @Test fun clampingAndTurkishFormattingCoverBothLimitsAndCorruptValues() {
        assertEquals(-10_000L, clampSubtitleDelay(Long.MIN_VALUE))
        assertEquals(10_000L, clampSubtitleDelay(Long.MAX_VALUE))
        assertEquals(300L, clampSubtitleDelay(300))
        assertEquals("+0,0 sn", formatSubtitleDelay(0))
        assertEquals("+0,3 sn", formatSubtitleDelay(300))
        assertEquals("-0,1 sn", formatSubtitleDelay(-100))
        assertEquals("-10,0 sn", formatSubtitleDelay(Long.MIN_VALUE))
        assertEquals("+10,0 sn", formatSubtitleDelay(Long.MAX_VALUE))
    }

    @Test fun persistenceIsPerItemAndVersionSurvivesReopeningAndReset() = runBlocking {
        val store = MemoryStore()
        val prefs = Preferences(store, { it }, { it })
        val hd = item("hd")
        val uhd = item("uhd")
        assertEquals(0L, prefs.subtitleDelay(hd))
        prefs.setSubtitleDelay(hd, 300)
        prefs.setSubtitleDelay(uhd, -1200)
        val reopened = Preferences(store, { it }, { it })
        assertEquals(300L, reopened.subtitleDelay(hd))
        assertEquals(-1200L, reopened.subtitleDelay(uhd))
        assertEquals(0L, reopened.subtitleDelay(item("other-episode")))
        reopened.setSubtitleDelay(hd, Long.MAX_VALUE)
        assertEquals(10_000L, prefs.subtitleDelay(hd))
        prefs.set(subtitleDelayKey(hd), "broken")
        assertEquals(0L, prefs.subtitleDelay(hd))
        prefs.set(subtitleDelayKey(hd), "-9999999")
        assertEquals(-10_000L, prefs.subtitleDelay(hd))
        prefs.setSubtitleDelay(hd, 0)
        assertEquals(0L, reopened.subtitleDelay(hd))
    }

    @Test fun backupRestoreRemapsSourceAndItemAndDropsMissingItems() = runBlocking {
        val prefs = Preferences(MemoryStore(), { it }, { it })
        val old = item("old-id", "old-source")
        prefs.setSubtitleDelay(old, -700)
        prefs.setSubtitleDelay(item("removed", "old-source"), 300)
        val backup = prefs.backupSettings()
        assertTrue(backup.any { it.name == subtitleDelayKey(old) && it.value == "-700" })
        val restored = Preferences(MemoryStore(), { it }, { it })
        restored.restoreSettings(backup, mapOf("old-source" to "new-source"), mapOf("old-id" to "new-id"))
        assertEquals(-700L, restored.subtitleDelay(item("new-id", "new-source")))
        assertEquals(1, restored.backupSettings().size)
    }
}
