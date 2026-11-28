package dev.lumenara.data

import org.json.JSONArray
import org.json.JSONObject

internal const val wp12PendingRestoreSetting = "secret_wp12_pending_restore"

/** A restore queue is independent of the expiring device-local delete/re-add history pool. */
internal data class Wp12PendingRestore(
    val data: BackupData,
    val sources: Map<String, String>,
    val ids: Set<String>,
)

internal fun encodeWp12PendingRestores(pending: List<Wp12PendingRestore>): String = JSONArray().apply {
    pending.forEach { row -> put(JSONObject().put("version", 1)
        .put("data", BackupCodec.encode(row.data.copy(settings = row.data.settings.filterNot { it.name == wp12PendingRestoreSetting })))
        .put("sources", JSONObject(row.sources)).put("ids", JSONArray(row.ids.toList()))) }
}.toString()

internal fun decodeWp12PendingRestores(value: String): List<Wp12PendingRestore> {
    if (value.isEmpty()) return emptyList()
    val rows = JSONArray(value)
    require(rows.length() <= 100_000)
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        require(row.getInt("version") == 1)
        val payload = row.getJSONObject("data")
        val settings = payload.getJSONArray("settings")
        require((0 until settings.length()).none { settings.getJSONObject(it).getString("name") == wp12PendingRestoreSetting })
        val data = BackupCodec.decode(payload)
        val targets = row.getJSONObject("sources")
        val sources = data.sources.associate { it.id to targets.getString(it.id) }
        require(sources.values.all { it.isNotBlank() } && sources.values.distinct().size == sources.size)
        val ids = row.getJSONArray("ids").let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }
        val itemIds = data.items.map { it.id }.toSet()
        require(ids.all { it in itemIds })
        Wp12PendingRestore(data, sources, ids)
    }
}
