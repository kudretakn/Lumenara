package dev.streamora.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore(name = "preferences")

class Preferences(context: Context) {
    private val store = context.applicationContext.settings

    fun text(key: String, fallback: String = "") =
        store.data.map { it[stringPreferencesKey(key)] ?: fallback }

    suspend fun set(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    fun connections(source: String) =
        store.data.map {
            val limit =
                it[stringPreferencesKey("limit_$source")]?.toIntOrNull()?.coerceIn(1, 4) ?: 1
            val provider = it[stringPreferencesKey("provider_$source")]?.toIntOrNull() ?: 4
            minOf(limit, provider.coerceIn(1, 4))
        }
}
