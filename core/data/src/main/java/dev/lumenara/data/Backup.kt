package dev.lumenara.data

import dev.lumenara.catalog.contentKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

internal data class BackupSetting(val name: String, val type: String, val value: String)
internal fun isBackupSetting(name: String) = name != orphanHistorySetting && !name.startsWith("secret_refresh_") &&
    !name.startsWith("series_expanded_") && !name.startsWith("catalog_identity_") && !name.startsWith("update_")
internal data class BackupSource(val id: String, val name: String, val spec: SourceSpec)
internal data class BackupFeed(val id: String, val source: String, val address: String)
// Only identities needed for user state are exported, never catalog metadata.
internal data class BackupItem(val id: String, val source: String, val address: String, val parentAddress: String,
    val providerId: String = "", val tvgName: String = "", val tvgGroup: String = "",
    val kind: String = "", val parentProviderId: String = "")
internal data class BackupData(
    val settings: List<BackupSetting>,
    val sources: List<BackupSource>,
    val feeds: List<BackupFeed>,
    val items: List<BackupItem>,
    val marks: List<Mark>,
    val mappings: List<Mapping>,
    val tmdbToken: String = "",
    val redirects: List<ItemRedirect> = emptyList(),
)

class BackupPreview internal constructor(internal val data: BackupData) {
    val summary: String get() = "${data.sources.size} kaynak, ${data.marks.size} izleme kaydı, " +
        "${data.marks.count { it.favorite }} favori, ${data.marks.count { it.listed }} liste kaydı, " +
        "${data.feeds.size} EPG adresi, ${data.mappings.size} eşleştirme, ${data.settings.count { isBackupSetting(it.name) }} ayar"
}

data class BackupRestoreResult(val skipped: Int, val guideFailures: Int)

/** Version 1 is deliberately the only accepted format; future versions need an explicit migration. */
internal object BackupCodec {
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val VERSION = 1
    private const val ROUNDS = 210_000
    private const val AAD = "Lumenara|1|PBKDF2-HMAC-SHA256|210000|AES-256-GCM"

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size >= 6) { "Parola en az 6 karakter olmalı." }
        val spec = PBEKeySpec(password, salt, ROUNDS, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    fun encrypt(data: BackupData, password: CharArray): ByteArray {
        val plain = encode(data).toString().toByteArray(Charsets.UTF_8)
        try {
            require(plain.size <= MAX_FILE_BYTES / 2) { "Yedek boyutu sınırı aşıldı." }
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val base64 = Base64.getEncoder()
            return JSONObject().put("format", "lumenara-backup").put("version", VERSION)
                .put("kdf", "PBKDF2-HMAC-SHA256").put("iterations", ROUNDS)
                .put("cipher", "AES-256-GCM").put("salt", base64.encodeToString(salt))
                .put("iv", base64.encodeToString(iv))
                .put("data", base64.encodeToString(cipher.doFinal(plain)))
                .toString().toByteArray(Charsets.UTF_8)
        } finally { plain.fill(0) }
    }

    fun decrypt(bytes: ByteArray, password: CharArray): BackupData {
        require(password.size >= 6) { "Parola en az 6 karakter olmalı." }
        try {
            require(bytes.size in 1..MAX_FILE_BYTES)
            val header = JSONObject(bytes.toString(Charsets.UTF_8))
            require(header.get("version") is Int || header.get("version") is Long)
            require(header.getString("format") == "lumenara-backup")
            if (header.getLong("version") != VERSION.toLong())
                throw IllegalArgumentException("Bu yedek sürümü desteklenmiyor.")
            require(header.get("iterations") is Int || header.get("iterations") is Long)
            require(header.getString("kdf") == "PBKDF2-HMAC-SHA256" && header.getLong("iterations") == ROUNDS.toLong())
            require(header.getString("cipher") == "AES-256-GCM")
            val base64 = Base64.getDecoder()
            val salt = base64.decode(header.getString("salt"))
            val iv = base64.decode(header.getString("iv"))
            val encrypted = base64.decode(header.getString("data"))
            require(salt.size == 16 && iv.size == 12 && encrypted.size in 16..(MAX_FILE_BYTES / 2 + 16))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(encrypted)
            return try { decode(JSONObject(plain.toString(Charsets.UTF_8))) }
            finally { plain.fill(0) }
        } catch (e: IllegalArgumentException) {
            if (e.message == "Bu yedek sürümü desteklenmiyor.") throw e
            throw IllegalArgumentException("Parola yanlış veya yedek dosyası bozuk.")
        } catch (_: Exception) {
            throw IllegalArgumentException("Parola yanlış veya yedek dosyası bozuk.")
        }
    }

    private fun <T> rows(values: List<T>, encode: (T) -> JSONObject) = JSONArray().apply {
        values.forEach { put(encode(it)) }
    }

    internal fun encode(data: BackupData) = JSONObject().put("version", VERSION)
        .put("settings", rows(data.settings.filter { isBackupSetting(it.name) }) { JSONObject().put("name", it.name).put("type", it.type).put("value", it.value) })
        .put("sources", rows(data.sources) { JSONObject().put("id", it.id).put("name", it.name).put("spec", JSONObject(it.spec.encode())) })
        .put("feeds", rows(data.feeds) { JSONObject().put("id", it.id).put("source", it.source).put("address", it.address) })
        .put("items", rows(data.items) { JSONObject().put("id", it.id).put("source", it.source).put("address", it.address).put("parent", it.parentAddress)
            .put("providerId", it.providerId).put("tvgName", it.tvgName).put("tvgGroup", it.tvgGroup)
            .put("kind", it.kind).put("parentProviderId", it.parentProviderId) })
        .put("marks", rows(data.marks) { JSONObject().put("id", it.id).put("favorite", it.favorite).put("listed", it.listed)
            .put("position", it.position).put("duration", it.duration).put("updated", it.updated) })
        .put("mappings", rows(data.mappings) { JSONObject().put("item", it.item).put("feed", it.feed).put("channel", it.channel) })
        .put("tmdb", data.tmdbToken)
        .put("redirects", rows(data.redirects) { JSONObject().put("oldId", it.oldId).put("newId", it.newId) })

    private fun <T> read(json: JSONObject, name: String, decode: (JSONObject) -> T): List<T> {
        val rows = json.getJSONArray(name)
        require(rows.length() <= 100_000)
        return (0 until rows.length()).map { decode(rows.getJSONObject(it)) }
    }

    internal fun decode(json: JSONObject): BackupData {
        require(json.get("version") is Int || json.get("version") is Long)
        require(json.getLong("version") == VERSION.toLong()) { "Bu yedek sürümü desteklenmiyor." }
        // org.json getters coerce strings/numbers on Android; enforce the wire schema first.
        fun strings(row: JSONObject, vararg names: String) = names.forEach { require(row.get(it) is String) }
        fun table(name: String, check: (JSONObject) -> Unit) { read(json, name) { check(it) } }
        strings(json, "tmdb")
        table("settings") { strings(it, "name", "type", "value") }
        table("sources") {
            strings(it, "id", "name")
            val spec = it.getJSONObject("spec")
            strings(spec, "address", "user", "pass")
            require(spec.get("http") is Boolean)
        }
        table("feeds") { strings(it, "id", "source", "address") }
        table("items") {
            strings(it, "id", "source", "address", "parent")
            for (field in listOf("providerId", "tvgName", "tvgGroup", "kind", "parentProviderId")) if (it.has(field)) strings(it, field)
        }
        table("marks") {
            strings(it, "id")
            require(it.get("favorite") is Boolean && it.get("listed") is Boolean)
            for (name in listOf("position", "duration", "updated"))
                require(it.get(name) is Int || it.get(name) is Long)
        }
        table("mappings") { strings(it, "item", "feed", "channel") }
        if (json.has("redirects")) table("redirects") { strings(it, "oldId", "newId") }
        val data = BackupData(
            read(json, "settings") { BackupSetting(it.getString("name"), it.getString("type"), it.getString("value")) },
            read(json, "sources") { BackupSource(it.getString("id"), it.getString("name"), SourceSpec.decode(it.getJSONObject("spec").toString())) },
            read(json, "feeds") { BackupFeed(it.getString("id"), it.getString("source"), it.getString("address")) },
            read(json, "items") { BackupItem(it.getString("id"), it.getString("source"), it.getString("address"), it.getString("parent"),
                it.optString("providerId"), it.optString("tvgName"), it.optString("tvgGroup"),
                it.optString("kind"), it.optString("parentProviderId")) },
            read(json, "marks") { Mark(it.getString("id"), it.getBoolean("favorite"), it.getBoolean("listed"),
                it.getLong("position"), it.getLong("duration"), it.getLong("updated")) },
            read(json, "mappings") { Mapping(it.getString("item"), it.getString("feed"), it.getString("channel")) },
            json.getString("tmdb"),
            if (json.has("redirects")) read(json, "redirects") { ItemRedirect(it.getString("oldId"), it.getString("newId")) } else emptyList(),
        )
        validate(data)
        return data.copy(settings = data.settings.filter { isBackupSetting(it.name) })
    }

    internal fun validate(data: BackupData) {
        require(listOf(data.settings.size, data.sources.size, data.feeds.size, data.items.size,
            data.marks.size, data.mappings.size, data.redirects.size).all { it <= 100_000 })
        fun unique(ids: List<String>) = ids.all { it.isNotBlank() } && ids.distinct().size == ids.size
        require(unique(data.sources.map { it.id }) && unique(data.feeds.map { it.id }) && unique(data.items.map { it.id }))
        require(unique(data.settings.map { it.name }) && unique(data.marks.map { it.id }) && unique(data.mappings.map { it.item }))
        val sourceIds = data.sources.map { it.id }.toSet()
        val items = data.items.associateBy { it.id }
        val feeds = data.feeds.associateBy { it.id }
        require(data.sources.all { it.name.isNotBlank() && it.spec.address.isNotBlank() })
        require(data.items.all { it.address.isNotBlank() && it.source in sourceIds && it.id == contentKey(it.source, it.address) })
        require(data.feeds.all { it.address.isNotBlank() && it.source in sourceIds && it.id == contentKey(it.source, it.address) })
        require(data.marks.all { it.id in items && it.position >= 0 && it.duration >= 0 && it.updated >= 0 })
        require(unique(data.redirects.map { it.oldId }) && data.redirects.all { it.newId in items && it.oldId != it.newId })
        require(data.mappings.all { it.channel.isNotBlank() && items[it.item]?.source == feeds[it.feed]?.source && it.item in items && it.feed in feeds })
        data.settings.forEach { validateSetting(it) }
        val recent = data.settings.firstOrNull { it.name == "recent_live" }?.value.orEmpty()
        require(recent.lines().filter(String::isNotBlank).all { it in items })
    }

    private fun validateSetting(setting: BackupSetting) {
        require(!setting.name.startsWith("secret_") || setting.type == "string")
        require(setting.name != "show_adult" || setting.type == "boolean")
        require(setting.name != "recent_live" || setting.type == "string")
        if (setting.name == wp12PendingRestoreSetting) decodeWp12PendingRestores(setting.value)
        when (setting.type) {
            "string" -> Unit
            "boolean" -> setting.value.toBooleanStrict()
            "int" -> setting.value.toInt()
            "long" -> setting.value.toLong()
            "float" -> require(setting.value.toFloat().isFinite())
            "double" -> require(setting.value.toDouble().isFinite())
            "strings" -> JSONArray(setting.value).let { rows ->
                for (i in 0 until rows.length()) require(rows.get(i) is String)
            }
            else -> error("Desteklenmeyen ayar türü.")
        }
    }
}
