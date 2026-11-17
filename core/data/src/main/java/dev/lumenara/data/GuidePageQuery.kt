package dev.lumenara.data

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.lumenara.catalog.searchable

data class CardGuideTarget(val itemId: String, val feed: String, val channel: String)

/** Resolve only composed cards. All programme presence probes use feed/channel indexes;
 * name normalization is bounded by this page, never the provider's channel count.
 */
internal fun guidePageTargetsQuery(rows: List<GuideChannel>): SimpleSQLiteQuery {
    require(rows.isNotEmpty() && rows.size <= 200)
    return SimpleSQLiteQuery("""WITH visible(itemId,source,tvgId,name) AS (
        VALUES ${rows.joinToString { "(?,?,?,?)" }}
        ), targets AS (
        SELECT c.itemId,f.id AS feed,CASE
            WHEN m.item IS NOT NULL THEN CASE WHEN m.feed=f.id THEN m.channel END
            WHEN c.tvgId!='' AND EXISTS(SELECT 1 FROM programmes direct
                WHERE direct.feed=f.id AND direct.channel=c.tvgId) THEN c.tvgId
            ELSE (SELECT CASE WHEN COUNT(DISTINCT n.channel)=1 THEN MIN(n.channel) END
                FROM epgNames n WHERE n.feed=f.id AND n.name=c.name)
            END AS channel
        FROM visible c CROSS JOIN feeds f ON f.source=c.source
        LEFT JOIN mappings m ON m.item=c.itemId
        WHERE EXISTS(SELECT 1 FROM programmes p WHERE p.feed=f.id)
        ) SELECT itemId,feed,channel FROM targets WHERE channel IS NOT NULL
        ORDER BY itemId,feed,channel""",
        rows.flatMap { listOf(it.id, it.source, it.tvgId, searchable(it.title)) }.toTypedArray())
}
