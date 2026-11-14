package dev.lumenara.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore(name = "preferences")

class Preferences(context: Context) {
    private val store = context.applicationContext.settings

    fun text(key: String, fallback: String = "") =
        store.data.map { it[stringPreferencesKey(key)] ?: fallback }

    suspend fun set(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    // Newline encoding preserves recency; DataStore edit makes concurrent updates atomic.
    val recentLive = store.data.map {
        it[stringPreferencesKey("recent_live")].orEmpty().lines()
            .filter(String::isNotBlank).distinct().take(20)
    }.distinctUntilChanged()

    suspend fun recordLive(id: String) {
        store.edit {
            val key = stringPreferencesKey("recent_live")
            val ids = it[key].orEmpty().lines().filter(String::isNotBlank)
            it[key] = (listOf(id) + ids).distinct().take(20).joinToString("\n")
        }
    }

    fun connections(source: String) =
        store.data.map {
            val limit =
                it[stringPreferencesKey("limit_$source")]?.toIntOrNull()?.coerceIn(1, 4) ?: 1
            val provider = it[stringPreferencesKey("provider_$source")]?.toIntOrNull() ?: 4
            minOf(limit, provider.coerceIn(1, 4))
        }
}
