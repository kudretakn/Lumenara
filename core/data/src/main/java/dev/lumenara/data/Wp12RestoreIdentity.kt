package dev.lumenara.data

import dev.lumenara.catalog.contentKey
import dev.lumenara.catalog.searchable
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/** Preparation owns disk stages; commit only publishes already prepared local data. */
internal class Wp12PreparedCatalog(
    val rows: List<CarryRow>,
    val matches: CarryMatch,
    val needsJournal: Boolean,
    val commit: suspend () -> Unit,
    private val cleanup: () -> Unit,
) : Closeable {
    override fun close() = cleanup()
}

private val wp12StageRoots = HashSet<String>()

/** Interrupted stages contain no committed state and can be discarded on the next import. */
internal fun wp12StageDirectory(cache: File): File = synchronized(wp12StageRoots) {
    val root = File(cache, "wp12-stages")
    if (wp12StageRoots.add(root.absolutePath)) check(root.deleteRecursively())
    check(root.mkdirs() || root.isDirectory)
    Files.createTempDirectory(root.toPath(), "catalog-").toFile()
}

private fun wp12Kind(item: BackupItem, spec: SourceSpec): String {
    if (item.kind.isNotEmpty()) return item.kind
    if (item.parentAddress.isNotEmpty()) return "EPISODE"
    if (spec.user.isEmpty()) return ""
    if (legacyProviderId(item.address, "SERIES").isNotEmpty()) return "SERIES"
    return listOf("LIVE", "MOVIE").firstOrNull { legacyProviderId(item.address, it).isNotEmpty() }.orEmpty()
}

private fun wp12Provider(item: BackupItem, spec: SourceSpec): String = item.providerId.ifEmpty {
    if (spec.user.isEmpty()) "" else legacyProviderId(item.address, wp12Kind(item, spec),
        item.parentProviderId.ifEmpty { legacyProviderId(item.parentAddress, "SERIES") })
}

internal fun wp12RestoreParents(items: List<BackupItem>, spec: SourceSpec): List<BackupItem> =
    (items.filter { wp12Kind(it, spec) == "SERIES" } + items.filter { it.parentAddress.isNotEmpty() }.map {
        BackupItem(contentKey(it.source, it.parentAddress), it.source, it.parentAddress, "",
            providerId = it.parentProviderId.ifEmpty {
                if (spec.user.isEmpty()) "" else legacyProviderId(it.parentAddress, "SERIES")
            }, kind = "SERIES")
    }).distinctBy { it.id }

/**
 * Called for ONE explicitly mapped source. No title fallback: a v1 backup may lack titles,
 * kinds or provider IDs. Only unique provider/TVG identities or exact locators are safe.
 * Episodes additionally require the same resolved parent; provider episode IDs alone do not
 * establish ownership. Duplicate identities on either side never choose a first rendition.
 */
internal fun wp12MatchRestoreItems(items: List<BackupItem>, source: String, spec: SourceSpec,
    incoming: List<CarryRow>): Map<String, String> {
    if (items.isEmpty()) return emptyMap()
    val targets = incoming.filter { it.source == source }.distinctBy { it.id }
    val result = linkedMapOf<String, String>()
    val claimed = mutableSetOf<String>()
    val blocked = mutableSetOf<String>()
    val punctuation = Regex("[^\\p{L}\\p{N}]+")
    fun normalized(value: String) = searchable(value).replace(punctuation, " ").trim()
    val byId = targets.associateBy { it.id }
    val byProvider = targets.filter { it.providerId.isNotEmpty() }.groupBy { it.providerId }
    fun tvg(name: String, group: String) = normalized(name) + "|" + normalized(group)
    val byTvg = targets.filter { it.kind == "LIVE" && it.tvgName.isNotBlank() }.groupBy { tvg(it.tvgName, it.tvgGroup) }
    fun match(rows: List<BackupItem>, parentIds: Map<String, String>) {
        fun compatible(saved: BackupItem, row: CarryRow): Boolean {
            val kind = wp12Kind(saved, spec)
            if (kind.isNotEmpty() && kind != row.kind) return false
            return if (saved.parentAddress.isEmpty()) row.parent.isEmpty() else
                parentIds[contentKey(saved.source, saved.parentAddress)] == row.parent && row.parent.isNotEmpty()
        }
        fun round(candidates: (BackupItem) -> List<CarryRow>) {
            // Include already resolved peers in uniqueness checks; otherwise the second of two
            // old renditions could acquire a provider ID that was never unique in the backup.
            val choices = rows.associate { saved -> saved.id to candidates(saved).filter { compatible(saved, it) } }
            val owners = choices.flatMap { (id, hits) -> hits.map { it.id to id } }.groupBy({ it.first }, { it.second })
            for (saved in rows) {
                if (saved.id in result || saved.id in blocked) continue
                val hits = choices.getValue(saved.id)
                if (hits.size > 1 || hits.any { owners.getValue(it.id).size > 1 }) {
                    blocked += saved.id
                    continue
                }
                val target = hits.singleOrNull() ?: continue
                if (target.id !in claimed) { result[saved.id] = target.id; claimed += target.id }
            }
        }
        round { saved -> listOfNotNull(byId[contentKey(source, saved.address)]) }
        round { saved -> byProvider[wp12Provider(saved, spec)].orEmpty() }
        // Playlist TVG names are only meaningful for live channels, with their group.
        round { saved -> if (spec.user.isEmpty() && saved.tvgName.isNotBlank())
            byTvg[tvg(saved.tvgName, saved.tvgGroup)].orEmpty() else emptyList() }
    }
    val parents = wp12RestoreParents(items, spec)
    match(parents, emptyMap())
    val parentIds = result.toMap()
    match(items.filterNot { it.id in parentIds }, parentIds)
    val savedIds = items.map { it.id }.toSet()
    return result.filterKeys { it in savedIds }
}

internal fun wp12RestoreParentIds(items: List<BackupItem>, source: String, spec: SourceSpec,
    incoming: List<CarryRow>): Set<String> {
    val needed = items.filter { it.parentAddress.isNotEmpty() }.map { contentKey(it.source, it.parentAddress) }.toSet()
    if (needed.isEmpty()) return emptySet()
    return wp12MatchRestoreItems(wp12RestoreParents(items, spec), source, spec, incoming)
        .filterKeys { it in needed }.values.toSet()
}

/** Match source accounts by full endpoint+user, preferring a compatible original UUID only. */
internal fun wp12RestoreSource(saved: BackupSource, candidates: List<Pair<Source, SourceSpec>>,
    used: Set<String>): Source? {
    val compatible = candidates.filter { (source, spec) -> source.id !in used &&
        spec.address == saved.spec.address && spec.user == saved.spec.user }
    return compatible.singleOrNull { it.first.id == saved.id }?.first ?: compatible.singleOrNull()?.first
}
