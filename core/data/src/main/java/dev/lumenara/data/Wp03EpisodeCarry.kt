package dev.lumenara.data

import dev.lumenara.catalog.episodeName
import dev.lumenara.catalog.hasEpisodeNumber
import org.json.JSONArray
import org.json.JSONObject

/** Direct expansion knows the parent; sibling renditions must not compete in identity matching. */
internal fun wp03EpisodeMatches(parent: CarryRow, old: List<CarryRow>, incoming: List<CarryRow>): CarryMatch {
    fun scoped(rows: List<CarryRow>) = rows.filter {
        it.source == parent.source && it.parent == parent.id && it.kind == "EPISODE"
    } + parent
    return matchCarryOver(scoped(old), scoped(incoming))
}

/** Share only explicit S/E numbers that are unique within every participating rendition. */
internal fun wp03SiblingMarks(incoming: List<CarryRow>, siblings: List<CarryRow>,
    marks: Map<String, Mark>): Map<String, Mark> {
    fun numbered(rows: List<CarryRow>) = rows.filter { hasEpisodeNumber(it.title) }
        .groupBy { episodeName(it.title, it.category).let { n -> n.season to n.episode } }
    val shared = numbered(siblings)
    return numbered(incoming).mapNotNull { (number, targets) ->
        val target = targets.singleOrNull() ?: return@mapNotNull null
        val candidates = shared[number].orEmpty()
        if (candidates.groupBy { it.parent }.any { it.value.size != 1 }) return@mapNotNull null
        val states = candidates.mapNotNull { marks[it.id] }
        val newest = states.maxByOrNull { it.updated } ?: return@mapNotNull null
        target.id to newest.copy(id = target.id, favorite = states.any { it.favorite },
            listed = states.any { it.listed })
    }.toMap()
}

/** Keep unresolved history and ambiguity peers when entries are omitted or skipped by parsing. */
internal fun wp03UnresolvedEpisodeIds(pending: OrphanHistory, incomingIds: Set<String>,
    matchedIds: Set<String>): List<String> = pending.rows
    .filter { it.kind == "EPISODE" && it.id !in incomingIds && it.id !in matchedIds }
    .map { it.id }

/** Validate the catalog structure; Xtream skips malformed individual entries as before. */
internal fun wp03EpisodeSeasons(response: JSONObject): JSONObject {
    val raw = response.opt("episodes")
    // Xtream also uses [] for an explicitly empty series. Preserve that successful response.
    val seasons = when {
        raw is JSONObject -> raw
        raw is JSONArray && raw.length() == 0 -> JSONObject()
        else -> throw SourceFailure("Sağlayıcı bölüm listesini eksik veya geçersiz döndürdü.")
    }
    for (season in seasons.keys()) {
        if (seasons.optJSONArray(season) == null)
            throw SourceFailure("Sağlayıcı sezon listesini geçersiz döndürdü.")
    }
    return seasons
}
