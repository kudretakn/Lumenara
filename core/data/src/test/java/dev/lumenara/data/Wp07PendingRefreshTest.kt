package dev.lumenara.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class Wp07PendingRefreshTest {
    private class MemoryStore : DataStore<StoredPreferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (StoredPreferences) -> StoredPreferences): StoredPreferences =
            transform(data.value).toPreferences().also { data.value = it }
    }
    private fun pending(prefs: Preferences, enqueue: (String) -> Unit) = Wp07PendingRefresh(
        { prefs.text(wp07PendingRefreshKey, "false").first().toBoolean() },
        { prefs.set(wp07PendingRefreshKey, it.toString()) }, { enqueue(it) })

    @Test fun defaultsFalseAndManyMissedOccurrencesRetainOneRequestUntilSuccess() = runTest {
        val store = MemoryStore()
        val prefs = Preferences(store, { it }, { it })
        val unique = mutableSetOf<String>()
        val pending = pending(prefs) { unique += it }
        assertFalse(pending.isPending())
        pending.retryIfPending()
        assertTrue(unique.isEmpty())
        repeat(100) { pending.request() }
        assertEquals(setOf("deferred-refresh"), unique)
        assertTrue(pending.isPending())
        // Re-create the owner against the same persisted preferences, as on process startup.
        val reopened = pending(Preferences(store, { it }, { it })) { unique += it }
        reopened.retryIfPending()
        assertTrue(reopened.isPending())
        reopened.completed()
        unique.clear()
        reopened.retryIfPending()
        assertTrue(unique.isEmpty())
        assertFalse(reopened.isPending())
    }

    @Test fun failedEnqueueLeavesDurableIntentForTheNextOwner() = runTest {
        val prefs = Preferences(MemoryStore(), { it }, { it })
        val interrupted = pending(prefs) { throw IllegalStateException("enqueue interrupted") }
        assertTrue(runCatching { interrupted.request() }.isFailure)
        assertTrue(interrupted.isPending())
        var enqueued = 0
        val reopened = pending(prefs) { enqueued++ }
        reopened.retryIfPending()
        assertEquals(1, enqueued)
        assertTrue(reopened.isPending()) // Enqueue alone must not acknowledge refresh completion.
    }
}
