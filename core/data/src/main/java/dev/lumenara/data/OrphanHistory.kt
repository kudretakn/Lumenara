package dev.lumenara.data

import dev.lumenara.catalog.*
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal val orphanHistoryLifetime = TimeUnit.DAYS.toMillis(30)
internal const val orphanHistorySetting = "secret_orphan_history"

/** Device-local, credential-free identity. A different account on the same server may recover it. */
internal fun orphanProvider(spec: SourceSpec): String? = runCatching {
    val uri = URI(spec.address)
    if (uri.scheme == "content") return contentKey("orphan-document", spec.address)
    if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val port = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80
    contentKey("orphan-provider", "${if (spec.user.isEmpty()) "playlist" else "xtream"}|$host|$port")
}.getOrNull()

internal data class OrphanHistory(
    val source: String,
    val provider: String,
    val deletedAt: Long,
    val rows: List<CarryRow>,
    val marks: List<Mark>,
    val members: List<Member>,
    val references: Set<String> = emptySet(),
    val redirects: List<ItemRedirect> = emptyList(),
) {
    fun alive(now: Long) = now < deletedAt || now - deletedAt < orphanHistoryLifetime
    fun pendingIds() = marks.map { it.id }.toSet() + members.map { it.id } + references + redirects.map { it.newId }
}

/** Keep identities only for user state and required episode parents, never the whole catalog. */
internal fun orphanHistory(source: String, provider: String, now: Long, rows: List<CarryRow>,
    marks: List<Mark>, members: List<Member>, references: Set<String>): OrphanHistory {
    val ids = rows.map { it.id }.toSet()
    val savedMarks = marks.filter { it.id in ids && (it.favorite || it.listed || it.position > 0 || it.updated > 0) }
    val savedMembers = members.filter { it.id in ids }
    val keep = (savedMarks.map { it.id } + savedMembers.map { it.id } + references).toMutableSet()
    // Preserve peers that could make a strong or fallback identity ambiguous. Omitting an
    // unmarked duplicate would turn a deliberately blocked match into a guessed rendition.
    val parents = rows.associateBy { it.id }
    fun normalized(value: String) = searchable(value).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun keys(row: CarryRow): Set<String> = buildSet {
        if (row.providerId.isNotBlank()) add("provider|${row.kind}|${row.providerId}")
        if (row.providerId.isEmpty()) {
            val group = normalized(row.tvgGroup.ifBlank { row.category })
            if (row.tvgId.isNotBlank()) add("tvg|${row.kind}|${row.tvgId.trim()}|$group")
            if (row.tvgName.isNotBlank()) add("name|${row.kind}|${normalized(row.tvgName)}|$group")
        }
        val name = productionName(row.title, row.year)
        when (row.kind) {
            "MOVIE", "SERIES" -> add("${row.kind}|${normalized(name.title)}|${name.year}")
            "LIVE" -> add("LIVE|${normalized(row.title)}")
            "EPISODE" -> if (hasEpisodeNumber(row.title)) {
                val episode = episodeName(row.title, row.category)
                val series = parents[row.parent]?.title ?: episode.series
                add("EPISODE|${normalized(productionName(series).title)}|${episode.season}|${episode.episode}")
            }
        }
        if (row.urlHash.isNotEmpty()) add("url|${row.kind}|${row.urlHash}")
        if (row.versionKey.isNotEmpty()) add("version|${row.versionKey}")
    }
    rows.filter { it.id in keep && it.parent.isNotEmpty() }.forEach { keep += it.parent }
    val selectedKeys = rows.filter { it.id in keep }.flatMap { keys(it) }.toSet()
    rows.filter { keys(it).any { key -> key in selectedKeys } }.forEach { keep += it.id }
    rows.filter { it.id in keep && it.parent.isNotEmpty() }.forEach { keep += it.parent }
    return OrphanHistory(source, provider, now, rows.filter { it.id in keep }.map { it.copy(legacyUrl = "") },
        savedMarks, savedMembers, references.intersect(ids))
}

/** Each deletion is an independent catalog; repeated identities across snapshots are not renditions. */
internal fun matchOrphanHistories(snapshots: List<OrphanHistory>, incoming: List<CarryRow>): CarryMatch {
    val matches = snapshots.map { snapshot ->
        val match = matchCarryOver(snapshot.rows, incoming, categoryFallback = true)
        match.copy(ids = match.ids.filterKeys { it in snapshot.pendingIds() })
    }
    return CarryMatch(matches.flatMap { it.ids.entries }.associate { it.key to it.value },
        matches.sumOf { it.ambiguous }, matches.sumOf { it.unmatched })
}

/** Keep every ambiguity peer and episode parent until all pending user state has been recovered. */
internal fun pendingOrphanHistory(snapshot: OrphanHistory, matched: Set<String>): OrphanHistory? =
    snapshot.copy(marks = snapshot.marks.filterNot { it.id in matched },
        members = snapshot.members.filterNot { it.id in matched }, references = snapshot.references - matched,
        redirects = snapshot.redirects.filterNot { it.newId in matched })
        .takeIf { it.pendingIds().isNotEmpty() }

internal fun mergeCarryMark(old: Mark, current: Mark?, id: String): Mark =
    (if (current != null && current.updated >= old.updated) current else old).copy(id = id,
        favorite = old.favorite || current?.favorite == true, listed = old.listed || current?.listed == true)

internal fun encodeOrphanHistory(snapshots: List<OrphanHistory>): String = JSONArray().apply {
    snapshots.forEach { snapshot -> put(JSONObject()
        .put("source", snapshot.source).put("provider", snapshot.provider).put("deleted", snapshot.deletedAt)
        .put("rows", JSONArray().apply { snapshot.rows.forEach { row -> put(JSONArray(listOf(
            row.id, row.source, row.title, row.category, row.kind, row.tvgId, row.year, row.parent,
            row.providerId, row.tvgName, row.tvgGroup, row.versionKey, row.urlHash))) } })
        .put("marks", JSONArray().apply { snapshot.marks.forEach { mark -> put(JSONArray(listOf(
            mark.id, mark.favorite, mark.listed, mark.position, mark.duration, mark.updated))) } })
        .put("references", JSONArray(snapshot.references.toList()))
        .put("redirects", JSONArray().apply { snapshot.redirects.forEach {
            put(JSONArray(listOf(it.oldId, it.newId)))
        } })
        .put("members", JSONArray().apply { snapshot.members.forEach { put(JSONArray(listOf(it.id, it.name))) } })) }
}.toString()

internal fun decodeOrphanHistory(value: String): List<OrphanHistory> {
    if (value.isEmpty()) return emptyList()
    val snapshots = JSONArray(value)
    return (0 until snapshots.length()).map { index ->
        val snapshot = snapshots.getJSONObject(index)
        val rows = snapshot.getJSONArray("rows")
        val marks = snapshot.getJSONArray("marks")
        val members = snapshot.getJSONArray("members")
        OrphanHistory(snapshot.getString("source"), snapshot.getString("provider"), snapshot.getLong("deleted"),
            (0 until rows.length()).map { rows.getJSONArray(it).let { row -> CarryRow(
                row.getString(0), row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                row.getString(5), row.getString(6), row.getString(7), row.getString(8), row.getString(9),
                row.getString(10), versionKey = row.getString(11), urlHash = row.optString(12)) } },
            (0 until marks.length()).map { marks.getJSONArray(it).let { mark -> Mark(mark.getString(0),
                mark.getBoolean(1), mark.getBoolean(2), mark.getLong(3), mark.getLong(4), mark.getLong(5)) } },
            (0 until members.length()).map { members.getJSONArray(it).let { member ->
                Member(member.getString(0), member.getString(1)) } },
            snapshot.optJSONArray("references")?.let { refs ->
                (0 until refs.length()).map { refs.getString(it) }.toSet()
            }.orEmpty(),
            snapshot.optJSONArray("redirects")?.let { redirects ->
                (0 until redirects.length()).map { redirects.getJSONArray(it).let { row ->
                    ItemRedirect(row.getString(0), row.getString(1))
                } }
            }.orEmpty())
    }
}
