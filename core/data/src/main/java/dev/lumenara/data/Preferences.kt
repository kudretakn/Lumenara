package dev.lumenara.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private val Context.settings by preferencesDataStore(name = "preferences")
private val firstSettingsRead = java.util.concurrent.atomic.AtomicBoolean()

class Preferences internal constructor(
    private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
    private val traceStartup: Boolean = false,
) {
    constructor(context: Context) : this(context.applicationContext, Vault(context.applicationContext))

    private constructor(context: Context, vault: Vault) : this(context.settings, vault::encrypt, vault::decrypt, true)

    private val data = flow {
        val start = if (traceStartup) android.os.SystemClock.elapsedRealtime() else 0L
        var initial = true
        store.data.collect { settings ->
            if (initial) {
                initial = false
                if (traceStartup && firstSettingsRead.compareAndSet(false, true))
                    StartupPerf.elapsed("startup_datastore_first_read", start)
            }
            emit(settings)
        }
    }

    val showAdult = data.map { it[booleanPreferencesKey("show_adult")] ?: false }
        .distinctUntilChanged()

    suspend fun setShowAdult(show: Boolean) {
        store.edit { it[booleanPreferencesKey("show_adult")] = show }
    }

    suspend fun secret(key: String): String = withContext(Dispatchers.IO) {
        text("secret_$key").first().takeIf { it.isNotEmpty() }?.let(decrypt).orEmpty()
    }

    suspend fun setSecret(key: String, value: String) = withContext(Dispatchers.IO) {
        set("secret_$key", if (value.isEmpty()) "" else encrypt(value))
    }

    internal suspend fun fetchState(key: String): FetchState {
        val value = secret("refresh_$key")
        return FetchState.decode(value)
    }

    // Only commit after a successful import; failed parsing must never acknowledge new validators.
    internal suspend fun setFetchState(key: String, state: FetchState) =
        setSecret("refresh_$key", state.encode())

    internal suspend fun updateState(repository: String): UpdateState =
        UpdateState.decode(text("update_$repository").first())

    // Claim before networking, including failed attempts, so restarts cannot hammer GitHub.
    internal suspend fun beginUpdateCheck(repository: String, automatic: Boolean, now: Long): Boolean {
        var claimed = false
        store.edit {
            val key = stringPreferencesKey("update_$repository")
            val previous = UpdateState.decode(it[key].orEmpty())
            if (!automatic || previous.checkedAt == 0L || now < previous.checkedAt || now - previous.checkedAt >= 3_600_000L) {
                it[key] = previous.copy(checkedAt = now).encode()
                claimed = true
            }
        }
        return claimed
    }

    internal suspend fun setUpdateRelease(repository: String, tag: String, etag: String, release: String) {
        store.edit {
            val key = stringPreferencesKey("update_$repository")
            it[key] = UpdateState.decode(it[key].orEmpty()).copy(tag = tag, etag = etag, release = release).encode()
        }
    }

    fun text(key: String, fallback: String = "") =
        data.map { it[stringPreferencesKey(key)] ?: fallback }

    suspend fun set(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    // Called under the durable restore journal, in the same commit as catalog state migration.
    internal suspend fun carryItems(ids: Map<String, String>, identities: Map<String, String>): Map<String, String> {
        val subtitleFiles = linkedMapOf<String, String>()
        store.edit { target ->
            val recent = stringPreferencesKey("recent_live")
            target[recent]?.let { value -> target[recent] = value.lines().map { ids[it] ?: it }
                .distinct().take(20).joinToString("\n") }
            val snapshot = target.asMap()
            for ((key, value) in snapshot) {
                val prefix = listOf("version_", "audio_", "text_", "delay_", "sub_", "subtitle_", "engine_", "series_expanded_")
                    .firstOrNull { key.name.startsWith(it) } ?: continue
                val identity = key.name.removePrefix(prefix)
                val replacement = ids[identity] ?: identities[identity] ?: identities.entries.firstOrNull {
                    identity.startsWith(it.key + "|")
                }?.let { it.value + identity.removePrefix(it.key) } ?: identity
                val newKey = stringPreferencesKey(prefix + replacement)
                if (value is String) {
                    val newValue = if (value.startsWith("id:")) ids[value.removePrefix("id:")]?.let { "id:$it" } ?: value else value
                    if (newKey == key || newKey !in snapshot) target[newKey] = newValue
                    if (prefix == "text_" && value == "downloaded" && newKey != key && newKey !in snapshot)
                        subtitleFiles[identity] = replacement
                    if (newKey != key) target.remove(key)
                }
            }
        }
        return subtitleFiles
    }

    internal suspend fun carryReferences(rows: List<CarryRow>): Set<String> {
        val settings = data.first().asMap()
        val names = settings.keys.map { it.name }.toSet()
        val chosen = settings.values.filterIsInstance<String>().filter { it.startsWith("id:") }
            .map { it.removePrefix("id:") }.toSet()
        val recent = settings[stringPreferencesKey("recent_live")]?.toString().orEmpty().lines().toSet()
        val parents = rows.associateBy { it.id }
        val identities = carryProductionIdentities(rows)
        return rows.filter { row ->
            val identity = carryPlaybackIdentity(row, identities, parents)
            row.id in chosen || row.id in recent || listOf("version_", "audio_", "text_", "delay_", "sub_", "subtitle_", "engine_")
                .any { it + row.id in names || it + identity in names }
        }.map { it.id }.toSet()
    }

    internal suspend fun backupSettings(): List<BackupSetting> = withContext(Dispatchers.IO) {
        rawSettings().filter { isBackupSetting(it.name) }.map {
            if (it.name.startsWith("secret_") && it.value.isNotEmpty()) it.copy(value = decrypt(it.value)) else it
        }
    }

    internal suspend fun restoreSettings(
        settings: List<BackupSetting>, sources: Map<String, String>, items: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        // Prepare all secrets before the atomic DataStore edit; Keystore values are device specific.
        val sourcePattern = sources.keys.takeIf { it.isNotEmpty() }
            ?.joinToString("|") { Regex.escape(it) }?.let { Regex(it) }
        val prepared = settings.filter { isBackupSetting(it.name) }.map { saved ->
            val name = sourcePattern?.replace(saved.name) { sources.getValue(it.value) } ?: saved.name
            val value = when {
                saved.name == "recent_live" -> saved.value.lines().mapNotNull { items[it] }.distinct().take(20).joinToString("\n")
                saved.name.startsWith("version_") && saved.value.startsWith("id:") ->
                    items[saved.value.removePrefix("id:")]?.let { "id:$it" }.orEmpty()
                saved.name.startsWith("secret_") && saved.value.isNotEmpty() -> encrypt(saved.value)
                else -> saved.value
            }
            saved.copy(name = name, value = value)
        }
        require(prepared.map { it.name }.distinct().size == prepared.size) { "Yinelenen ayar adı." }
        replaceSettings(prepared)
    }

    // Rollback retains encrypted secrets and catalog validators, but never rewinds updater state.
    internal suspend fun snapshotSettings(): String = JSONArray().apply {
        rawSettings().forEach { put(org.json.JSONObject().put("name", it.name).put("type", it.type).put("value", it.value)) }
    }.toString()

    internal suspend fun restoreSnapshot(snapshot: String) {
        val rows = JSONArray(snapshot)
        replaceSettings((0 until rows.length()).map { index ->
            rows.getJSONObject(index).let { BackupSetting(it.getString("name"), it.getString("type"), it.getString("value")) }
        })
    }

    private suspend fun rawSettings(): List<BackupSetting> = data.first().asMap()
        .filterKeys { !it.name.startsWith("update_") }.map { (key, value) ->
        val type = when (value) {
            is String -> "string"
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is Double -> "double"
            is Set<*> -> "strings"
            else -> error("Desteklenmeyen ayar türü.")
        }
        BackupSetting(key.name, type, if (value is Set<*>) JSONArray(value.toList()).toString() else value.toString())
    }

    private suspend fun replaceSettings(settings: List<BackupSetting>) {
        store.edit { target ->
            // Missing keys represent defaults in the snapshot, including unset secrets.
            target.asMap().keys.filterNot { it.name.startsWith("update_") }.forEach { target.remove(it) }
            for ((name, type, value) in settings.filterNot { it.name.startsWith("update_") }) when (type) {
                "string" -> target[stringPreferencesKey(name)] = value
                "boolean" -> target[booleanPreferencesKey(name)] = value.toBooleanStrict()
                "int" -> target[intPreferencesKey(name)] = value.toInt()
                "long" -> target[longPreferencesKey(name)] = value.toLong()
                "float" -> target[floatPreferencesKey(name)] = value.toFloat()
                "double" -> target[doublePreferencesKey(name)] = value.toDouble()
                "strings" -> {
                    val rows = JSONArray(value)
                    target[stringSetPreferencesKey(name)] = (0 until rows.length()).map { rows.getString(it) }.toSet()
                }
            }
        }
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
    val recentLive = data.map {
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
        data.map {
            val limit =
                it[stringPreferencesKey("limit_$source")]?.toIntOrNull()?.coerceIn(1, 4) ?: 1
            val provider = it[stringPreferencesKey("provider_$source")]?.toIntOrNull() ?: 4
            minOf(limit, provider.coerceIn(1, 4))
        }
}
