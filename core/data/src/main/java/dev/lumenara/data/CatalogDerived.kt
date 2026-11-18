package dev.lumenara.data

import androidx.room.*
import dev.lumenara.catalog.displayTitle
import dev.lumenara.catalog.isAdultCategory
import dev.lumenara.catalog.searchable
import dev.lumenara.catalog.categorySortKey
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "catalog_derived_state", foreignKeys = [ForeignKey(entity = Source::class,
    parentColumns = ["id"], childColumns = ["source"], onDelete = ForeignKey.CASCADE)])
data class CatalogDerivedState(@PrimaryKey val source: String, val phase: String = "derive",
    val cursor: String = "", val title: String = "", val number: Int = 0, val safeNumber: Int = 0)

@Entity(tableName = "category_counts", primaryKeys = ["source", "kind", "category"],
    foreignKeys = [ForeignKey(entity = Source::class, parentColumns = ["id"], childColumns = ["source"], onDelete = ForeignKey.CASCADE)])
data class CategoryCount(val source: String, val kind: String, val category: String, val count: Int,
    val isAdult: Boolean, val sortOrder: String = "", val searchKey: String = "")

@Entity(tableName = "catalog_stats", primaryKeys = ["source", "kind", "showAdult"],
    foreignKeys = [ForeignKey(entity = Source::class, parentColumns = ["id"], childColumns = ["source"], onDelete = ForeignKey.CASCADE)])
data class CatalogStat(val source: String, val kind: String, val showAdult: Boolean, val count: Int)

data class DerivedAssignment(val id: String, val cleanTitle: String, val sortKey: String, val isAdult: Boolean)
data class ChannelAssignment(val id: String, val channelNumber: Int, val safeChannelNumber: Int)
data class DerivedTitleCandidate(val id: String, val title: String, val displayTitle: String, val category: String)
data class NumberCandidate(val id: String, val sortKey: String, val isAdult: Boolean)
data class StoredChannelNumber(val id: String, val channelNumber: Int, val safeChannelNumber: Int)
data class DerivedCandidate(val id: String, val source: String, val kind: String, val title: String,
    val displayTitle: String, val category: String, val isAdult: Boolean,
    val categoryWinner: Boolean, val winner: Boolean, val safeWinner: Boolean)

@Dao
interface CatalogDerivedDao {
    // Sources/state only: a completed catalog never gets rechecked by scanning items.
    @Query("""SELECT s.id FROM sources s LEFT JOIN catalog_derived_state d ON d.source=s.id
        WHERE d.source IS NULL OR d.phase!='done' ORDER BY s.id LIMIT 1""")
    suspend fun pendingSource(): String?
    @Query("SELECT * FROM catalog_derived_state WHERE source=:source")
    suspend fun state(source: String): CatalogDerivedState?
    @Upsert suspend fun save(state: CatalogDerivedState)
    @Upsert suspend fun save(rows: List<CategoryCount>)
    @Upsert suspend fun stats(rows: List<CatalogStat>)
    @androidx.room.Update(entity = Item::class) suspend fun derive(rows: List<DerivedAssignment>)
    @androidx.room.Update(entity = Item::class) suspend fun channels(rows: List<ChannelAssignment>)
    @Query("DELETE FROM category_counts WHERE source=:source") suspend fun clearCategories(source: String)
    @Query("DELETE FROM catalog_stats WHERE source=:source") suspend fun clearStats(source: String)
    @Query("SELECT * FROM category_counts WHERE source=:source") suspend fun categories(source: String): List<CategoryCount>
    @Query("SELECT * FROM catalog_stats WHERE source=:source") suspend fun stats(source: String): List<CatalogStat>
    @Query("""SELECT kind,SUM(count) AS count FROM catalog_stats WHERE showAdult=:showAdult
        AND (:source='' OR source=:source) GROUP BY kind ORDER BY kind""")
    fun totals(source: String, showAdult: Boolean): Flow<List<HubTotal>>
    @Query("""SELECT id,title,displayTitle,category FROM items INDEXED BY index_items_source_id WHERE source=:source AND id>:cursor ORDER BY id LIMIT :limit""")
    suspend fun rows(source: String, cursor: String, limit: Int): List<DerivedTitleCandidate>
    @Query("""SELECT id,sortKey,isAdult FROM items INDEXED BY index_items_source_kind_sortKey_id
        WHERE source=:source AND kind='LIVE' AND (sortKey,id)>(:title,:id)
        ORDER BY sortKey,id LIMIT :limit""")
    suspend fun channels(source: String, title: String, id: String, limit: Int): List<NumberCandidate>
    @Query("""SELECT i.id,i.source,i.kind,i.title,i.displayTitle,i.category,i.isAdult,
        (i.versionKey='' OR NOT EXISTS(SELECT 1 FROM items v WHERE v.versionKey=i.versionKey
            AND v.category=i.category AND v.versionRank<i.versionRank)) AS categoryWinner,
        (i.versionKey='' OR i.isRepresentative=1) AS winner,
        (i.isAdult=0 AND (i.versionKey='' OR NOT EXISTS(SELECT 1 FROM items v
            WHERE v.versionKey=i.versionKey AND v.isAdult=0 AND v.versionRank<i.versionRank))) AS safeWinner
        FROM items i INDEXED BY index_items_source_id WHERE i.source=:source AND i.id>:cursor ORDER BY i.id LIMIT :limit""")
    suspend fun counts(source: String, cursor: String, limit: Int): List<DerivedCandidate>
    @Query("SELECT id,channelNumber,safeChannelNumber FROM items WHERE id IN (:ids) AND kind='LIVE' AND (:source='' OR source=:source)")
    suspend fun numbered(source: String, ids: List<String>): List<StoredChannelNumber>
    @Query("SELECT * FROM items WHERE source=:source AND channelNumber=:number AND kind='LIVE' LIMIT 1")
    suspend fun channel(source: String, number: Int): Item?
    @Query("SELECT * FROM items WHERE source=:source AND safeChannelNumber=:number AND kind='LIVE' LIMIT 1")
    suspend fun safeChannel(source: String, number: Int): Item?

}

data class HubTotal(val kind: String, val count: Int)

/** Source-local, resumable work. Cursor and output commit together; never invoked on DB open.
 * Refresh calls this before publishing; upgrades resume it after the startup quiet window.
 */
class CatalogDerivedBackfill(private val db: LibraryDb) {
    private val dao = db.derived()
    suspend fun reset(source: String) = db.withTransaction {
        dao.clearCategories(source)
        dao.clearStats(source)
        dao.save(CatalogDerivedState(source))
    }
    suspend fun runBatch(source: String, limit: Int = 250): Boolean {
        require(limit in 1..500)
        val start = android.os.SystemClock.elapsedRealtime()
        var phase = "done"
        val more = db.withTransaction {
            val state = dao.state(source) ?: CatalogDerivedState(source)
            phase = state.phase
            var next = state
            when (state.phase) {
                "derive" -> {
                    val rows = dao.rows(source, state.cursor, limit)
                    dao.derive(rows.map { row ->
                        val clean = row.displayTitle.ifEmpty { row.title }.displayTitle()
                        DerivedAssignment(row.id, clean, searchable(clean), isAdultCategory(row.category))
                    })
                    next = if (rows.isEmpty()) state.copy(phase = "counts", cursor = "")
                        else state.copy(cursor = rows.last().id)
                }
                "counts" -> {
                    val rows = dao.counts(source, state.cursor, limit)
                    val categories = dao.categories(source).associateBy { it.kind to it.category }.toMutableMap()
                    val stats = dao.stats(source).associateBy { it.kind to it.showAdult }.toMutableMap()
                    rows.filter { it.kind in listOf("LIVE", "MOVIE", "SERIES") }.forEach { row ->
                        if (row.categoryWinner) {
                            val key = row.kind to row.category
                            val old = categories[key] ?: CategoryCount(source, row.kind, row.category, 0,
                                row.isAdult, searchKey = searchable(row.category))
                            categories[key] = old.copy(count = old.count + 1)
                        }
                        for ((show, winner) in listOf(true to row.winner, false to row.safeWinner)) if (winner) {
                            val key = row.kind to show
                            val old = stats[key] ?: CatalogStat(source, row.kind, show, 0)
                            stats[key] = old.copy(count = old.count + 1)
                        }
                    }
                    dao.save(categories.values.toList())
                    dao.stats(stats.values.toList())
                    next = if (rows.isEmpty()) {
                        dao.save(categories.values.map { it.copy(sortOrder = categorySortKey(it.category)) })
                        state.copy(phase = "channels", cursor = "")
                    } else state.copy(cursor = rows.last().id)
                }
                "channels" -> {
                    val rows = dao.channels(source, state.title, state.cursor, limit)
                    var number = state.number
                    var safe = state.safeNumber
                    dao.channels(rows.map { ChannelAssignment(it.id, ++number, if (it.isAdult) 0 else ++safe) })
                    next = if (rows.isEmpty()) state.copy(phase = "done") else state.copy(
                        cursor = rows.last().id, title = rows.last().sortKey, number = number, safeNumber = safe)
                }
                "done" -> return@withTransaction false
                else -> error("Unknown catalog backfill phase")
            }
            dao.save(next)
            next.phase != "done"
        }
        when (phase) {
            "derive" -> StartupPerf.elapsed("catalog_titles_batch", start)
            "counts" -> StartupPerf.elapsed("catalog_counts_batch", start)
            "channels" -> StartupPerf.elapsed("catalog_channel_numbers_batch", start)
        }
        StartupPerf.elapsed("catalog_derived_batch", start)
        if (!more) StartupPerf.elapsed("catalog_derived_done", start)
        return more
    }
}
