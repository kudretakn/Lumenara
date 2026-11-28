package dev.lumenara.data

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.lumenara.catalog.searchable

// 300 triples plus query/from/now stay below Android 9's 999 bind parameters.
internal const val WP13_GUIDE_SEARCH_TARGETS = 300
internal data class Wp13GuideSearchTarget(val itemId: String, val feed: String, val channel: String)

/** Mapping priority, source ownership and visibility have already been resolved.
 * Probe only eligible feed/channel ranges, then limit hits rather than raw programmes.
 */
internal fun wp13GuideSearchQuery(
    targets: List<Wp13GuideSearchTarget>, query: String, from: Long, now: Long,
): SimpleSQLiteQuery {
    require(targets.isNotEmpty() && targets.size <= WP13_GUIDE_SEARCH_TARGETS)
    return SimpleSQLiteQuery("""WITH eligible(itemId,feed,channel) AS (
        VALUES ${targets.joinToString { "(?,?,?)" }}
        ), request(query,since,at) AS (VALUES (?,?,?))
        SELECT e.itemId,p.* FROM request r CROSS JOIN eligible e
        CROSS JOIN programmes p INDEXED BY index_programmes_feed_channel_end
        ON p.feed=e.feed AND p.channel=e.channel AND p.end>r.since
        WHERE instr(p.search,r.query)>0
        ORDER BY CASE WHEN p.start<=r.at AND p.end>r.at THEN 0 WHEN p.start>r.at THEN 1 ELSE 2 END,
            CASE WHEN p.start>r.at OR p.end>r.at THEN p.start ELSE -p.start END,e.itemId,p.feed,p.channel
        LIMIT 60""", (targets.flatMap { listOf(it.itemId, it.feed, it.channel) } +
        listOf(searchable(query), from, now)).toTypedArray())
}

internal fun wp13ProgramHitOrder(now: Long): Comparator<ProgramHit> {
    fun rank(slot: Slot) = when {
        slot.start <= now && slot.end > now -> 0
        slot.start > now -> 1
        else -> 2
    }
    return compareBy<ProgramHit> { rank(it.slot) }
        .thenBy { if (rank(it.slot) == 2) -it.slot.start else it.slot.start }
        .thenBy { it.itemId }.thenBy { it.slot.feed }.thenBy { it.slot.channel }
}
