package dev.lumenara.data

import androidx.room.Embedded
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.lumenara.catalog.searchable

data class LiveNow(val itemId: String, @Embedded val slot: Slot)

/** Resolve only this viewport. Preserve explicit mapping, direct tvgId, then unique-name
 * fallback within each source/feed. Every programme probe uses an existing feed/channel index.
 * VALUES keeps title normalization and allocation bounded by the visible page, not the catalog.
 */
internal fun liveNowQuery(rows: List<Item>, now: Long): SimpleSQLiteQuery {
    require(rows.isNotEmpty() && rows.size <= 200)
    val args = rows.flatMap { listOf(it.id, it.source, it.tvgId, searchable(it.title)) } + listOf(now, now)
    return SimpleSQLiteQuery("""WITH visible(itemId,source,tvgId,name) AS (
        VALUES ${rows.joinToString { "(?,?,?,?)" }}
        ) SELECT c.itemId,p.* FROM visible c
        CROSS JOIN feeds f ON f.source=c.source
        LEFT JOIN mappings m ON m.item=c.itemId
        CROSS JOIN programmes p INDEXED BY index_programmes_feed_channel_end
        ON p.feed=f.id AND p.channel=CASE
            WHEN m.item IS NOT NULL THEN CASE WHEN m.feed=f.id THEN m.channel END
            WHEN c.tvgId!='' AND EXISTS(SELECT 1 FROM programmes direct
                WHERE direct.feed=f.id AND direct.channel=c.tvgId) THEN c.tvgId
            ELSE (SELECT CASE WHEN COUNT(DISTINCT n.channel)=1 THEN MIN(n.channel) END
                FROM epgNames n WHERE n.feed=f.id AND n.name=c.name)
            END
        WHERE p.start<=? AND p.end>?
        ORDER BY p.start,p.feed,p.channel""", args.toTypedArray())
}
