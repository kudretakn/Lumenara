package dev.lumenara.data

import dev.lumenara.catalog.*
import java.net.URI
import java.net.URLDecoder

/**
 * Persistent item references (audited for provider locator changes):
 * Room: items.id/parent/versionKey, marks.id, collections.id, mappings.item,
 * item_redirects.oldId/newId; restore_pending.snapshot contains the encrypted settings undo image.
 * DataStore: recent_live values, version_<production> values (id:<item>), audio_/text_/delay_
 * production or episode keys, and series_expanded_<item>. Legacy sub_/subtitle_/engine_ keys
 * are also recognized. Production keys include source, kind, title/year and optional season/episode.
 * Backup: items.id/parentAddress, marks.id, mappings.item, redirects and the same settings keys.
 * Android TV: WatchNextPrograms.content_id/intent_uri; watch_next_state/excluded stores IDs.
 * Rebuildable caches: tmdb/<item>.json, subtitle files hashed from the production/episode key,
 * guide card pages/channel-number maps and Compose saved focus/playback navigation state.
 * Feed/channel IDs in programmes/epgNames are provider IDs, not items.id.
 *
 * Small catalog projection: no plot, artwork, cast or sealed URL on current schema rows.
 */
data class CarryRow(
    val id: String, val source: String, val title: String, val category: String,
    val kind: String, val tvgId: String, val year: String, val parent: String,
    val providerId: String, val tvgName: String, val tvgGroup: String,
    val legacyUrl: String = "",
    val versionKey: String = "",
    val urlHash: String = "",
) {
    fun preferenceIdentity(): String = "$source|$kind|${productionName(title, year).key()}"
}

data class CarryMatch(val ids: Map<String, String>, val ambiguous: Int, val unmatched: Int)

private val carryPunctuation = Regex("[^\\p{L}\\p{N}]+")
private fun normalized(value: String) = searchable(value).replace(carryPunctuation, " ").trim()

/** Never choose an arbitrary rendition. Duplicate keys on either side block weaker matches. */
fun matchCarryOver(oldRows: List<CarryRow>, newRows: List<CarryRow>, categoryFallback: Boolean = false): CarryMatch {
    // Import upserts duplicate locators; match the final persisted row for each primary key.
    val oldParents = oldRows.associateBy { it.id }
    val newParents = newRows.associateBy { it.id }
    val old = oldParents.values.toList()
    val new = newParents.values.toList()
    val result = linkedMapOf<String, String>()
    val claimed = mutableSetOf<String>()
    val blockedOld = mutableSetOf<String>()
    val blockedNew = mutableSetOf<String>()
    val newIds = new.map { it.id }.toSet()
    if (old.all { it.id in newIds }) return CarryMatch(emptyMap(), 0, 0)
    old.filter { it.id in newIds }.forEach { result[it.id] = it.id; claimed += it.id }
    fun round(key: (CarryRow, Map<String, CarryRow>) -> String?) {
        val a = old.groupBy { key(it, oldParents) }
        val b = new.groupBy { key(it, newParents) }
        for ((identity, left) in a) {
            if (identity == null) continue
            val right = b[identity] ?: continue
            if (left.size != 1 || right.size != 1) {
                blockedOld += left.filter { it.id !in result }.map { it.id }
                blockedNew += right.filter { it.id !in claimed }.map { it.id }
                continue
            }
            val from = left.single().id
            val to = right.single().id
            if (from !in result && to !in claimed && from !in blockedOld && to !in blockedNew) {
                result[from] = to
                claimed += to
            }
        }
    }
    round { row, _ -> row.providerId.takeIf { it.isNotBlank() }?.let { "${row.kind}|$it" } }
    round { row, _ -> row.urlHash.takeIf { it.isNotEmpty() }?.let { "${row.kind}|$it" } }
    round { row, _ ->
        if (row.providerId.isNotEmpty()) null else row.tvgId.takeIf { it.isNotBlank() }
            ?.let { "${row.kind}|${it.trim()}|${normalized(row.tvgGroup.ifBlank { row.category })}" }
    }
    round { row, _ ->
        if (row.providerId.isNotEmpty()) null else row.tvgName.takeIf { it.isNotBlank() }
            ?.let { "${row.kind}|${normalized(it)}|${normalized(row.tvgGroup.ifBlank { row.category })}" }
    }
    round { row, parents ->
        val name = productionName(row.title, row.year)
        val category = if (categoryFallback) "|${normalized(row.category)}" else ""
        when (row.kind) {
            "MOVIE" -> normalized(name.title).takeIf { it.isNotBlank() }?.let { "MOVIE|$it|${name.year}$category" }
            "SERIES" -> normalized(name.title).takeIf { it.isNotBlank() }?.let { "SERIES|$it|${name.year}$category" }
            "LIVE" -> normalized(row.title).takeIf { it.isNotBlank() }?.let { "LIVE|$it$category" }
            "EPISODE" -> if (!hasEpisodeNumber(row.title)) null else episodeName(row.title, row.category).let {
                val series = parents[row.parent]?.title ?: it.series
                "EPISODE|${normalized(productionName(series).title)}|${it.season}|${it.episode}$category"
            }
            else -> null
        }
    }
    return CarryMatch(result.filter { it.key != it.value }, blockedOld.size,
        old.count { it.id !in result && it.id !in blockedOld })
}

/** v11 has sealed locators but no explicit provider ID. Recover only from known Xtream paths. */
internal fun legacyProviderId(address: String, kind: String, seriesId: String = ""): String = runCatching {
    val uri = URI(address)
    if (kind == "SERIES") uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("series_id=") }
        ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
    else {
        val parts = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val type = when (kind) { "LIVE" -> "live"; "MOVIE" -> "movie"; "EPISODE" -> "series"; else -> "" }
        if (type.isEmpty() || parts.size < 4 || parts[parts.size - 4] != type) ""
        else parts.last().substringBeforeLast('.').let { if (kind == "EPISODE") "$seriesId:$it" else it }
    }
}.getOrDefault("")

internal fun carryPreferenceIdentities(old: List<CarryRow>, new: List<CarryRow>, ids: Map<String, String>): Map<String, String> {
    val oldIdentities = carryProductionIdentities(old)
    val newIdentities = carryProductionIdentities(new)
    val oldParents = old.associateBy { it.id }
    val newParents = new.associateBy { it.id }
    return old.filter { it.id in ids }.groupBy { carryPlaybackIdentity(it, oldIdentities, oldParents) }
        .mapNotNull { (identity, rows) ->
            val choices = rows.mapNotNull { newParents[ids[it.id]]?.let { target ->
                carryPlaybackIdentity(target, newIdentities, newParents)
            } }.distinct()
            choices.singleOrNull()?.let { identity to it }
        }.toMap()
}

internal fun carryProductionIdentities(rows: List<CarryRow>): Map<String, String> = rows
    .filter { it.kind != "EPISODE" }.groupBy { it.versionKey.ifBlank { it.id } }
    .values.flatMap { group ->
        val identity = group.minBy { it.id }.preferenceIdentity()
        group.map { it.id to identity }
    }.toMap()

internal fun carryPlaybackIdentity(row: CarryRow, identities: Map<String, String>, parents: Map<String, CarryRow>): String {
    if (row.kind != "EPISODE") return identities[row.id] ?: row.preferenceIdentity()
    val base = parents[row.parent]?.let { identities[it.id] ?: it.preferenceIdentity() } ?: row.preferenceIdentity()
    val number = episodeName(row.title, row.category)
    return "$base|${number.season}|${number.episode}"
}

internal fun remapSubtitleDelayKey(name: String, ids: Map<String, String>,
    sources: Map<String, String> = emptyMap()): String? {
    val identity = name.removePrefix("subtitle_delay_")
    val separator = identity.lastIndexOf('|')
    if (!name.startsWith("subtitle_delay_") || separator < 0) return null
    val itemId = identity.substring(separator + 1)
    val replacement = ids[itemId] ?: return null
    val source = identity.substring(0, separator)
    return "subtitle_delay_${sources[source] ?: source}|$replacement"
}
