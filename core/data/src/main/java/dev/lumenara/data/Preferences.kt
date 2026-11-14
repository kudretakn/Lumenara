package dev.lumenara.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.settings by preferencesDataStore(name = "preferences")

class Preferences(context: Context) {
    private val store = context.applicationContext.settings
    private val vault = Vault(context.applicationContext)

    suspend fun secret(key: String): String = withContext(Dispatchers.IO) {
        text("secret_$key").first().takeIf { it.isNotEmpty() }?.let(vault::decrypt).orEmpty()
    }

    suspend fun setSecret(key: String, value: String) = withContext(Dispatchers.IO) {
        set("secret_$key", if (value.isEmpty()) "" else vault.encrypt(value))
    }

    fun text(key: String, fallback: String = "") =
        store.data.map { it[stringPreferencesKey(key)] ?: fallback }

    suspend fun set(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    suspend fun playbackChoice(type: String, key: String): String {
        val local = text("${type}_$key").first()
        if (local.isNotEmpty()) return local
        val global = text(if (type == "audio") "audio" else "sub").first()
        return when {
            global == "Kapalı" -> "off"
            global in listOf("tr", "en", "de", "fr") -> global
            else -> ""
        }
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
