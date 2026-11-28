package dev.lumenara.data

import android.os.SystemClock
import androidx.room.*
import dev.lumenara.catalog.AudioVersion
import dev.lumenara.catalog.productionName
import dev.lumenara.catalog.searchable

/** Both the work and its cursor commit together. No catalog work runs in Room's open. */
@Entity(tableName = "version_backfill_state")
data class VersionBackfillState(
    @PrimaryKey val id: Int = 1,
    val phase: String = "normalize",
    val cursor: String = "",
    val elapsed: Long = 0,
    val total: Long = 0,
)

@Entity(tableName = "version_backfill_rows", indices = [Index("bucket", "year"), Index("resolvedKey", "audio", "id"), Index("source", "id")])
data class VersionBackfillRow(
    @PrimaryKey val id: String,
    val source: String,
    val bucket: String,
    val title: String,
    val year: String,
    val tmdb: String,
    val audio: Int,
    val resolvedKey: String = "",
)

@Dao
interface VersionBackfillDao {
    @Query("SELECT * FROM version_backfill_state WHERE id=1")
    suspend fun state(): VersionBackfillState?
    @Upsert suspend fun save(state: VersionBackfillState)
    @Upsert suspend fun save(rows: List<VersionBackfillRow>)

    @Query("""SELECT id,source,title,kind,search,year,category FROM items
        WHERE id>:cursor ORDER BY id LIMIT :limit""")
    suspend fun candidates(cursor: String, limit: Int): List<VersionCandidate>
    @Query("SELECT * FROM version_backfill_rows WHERE id>:cursor ORDER BY id LIMIT :limit")
    suspend fun rows(cursor: String, limit: Int): List<VersionBackfillRow>
    @Query("""SELECT id,source,title,kind,search,year,category FROM items INDEXED BY index_items_source_id
        WHERE source=:source AND id>:cursor AND (:generation<0 OR generation=:generation)
        ORDER BY id LIMIT :limit""")
    suspend fun sourceCandidates(source: String, generation: Long, cursor: String, limit: Int): List<VersionCandidate>
    @Query("SELECT * FROM version_backfill_rows WHERE source=:source AND id>:cursor ORDER BY id LIMIT :limit")
    suspend fun sourceRows(source: String, cursor: String, limit: Int): List<VersionBackfillRow>
    @Query("""DELETE FROM version_backfill_rows WHERE id IN
        (SELECT id FROM version_backfill_rows WHERE source=:source ORDER BY id LIMIT :limit)""")
    suspend fun cleanSource(source: String, limit: Int): Int
    // LIMIT 2 is enough to distinguish an unambiguous year/ID from conflicting remakes.
    @Query("SELECT DISTINCT year FROM version_backfill_rows WHERE bucket=:bucket AND year!='' LIMIT 2")
    suspend fun years(bucket: String): List<String>
    @Query("""SELECT DISTINCT tmdb FROM version_backfill_rows WHERE bucket=:bucket AND tmdb!=''
        AND (year=:year OR (:infer=1 AND year='')) LIMIT 2""")
    suspend fun tmdbIds(bucket: String, year: String, infer: Boolean): List<String>
    @Query("UPDATE version_backfill_rows SET resolvedKey=:key WHERE id=:id")
    suspend fun resolve(id: String, key: String)
    @Query("""SELECT COUNT(*) FROM version_backfill_rows WHERE resolvedKey=:key
        AND (audio<:audio OR (audio=:audio AND id<:id))""")
    suspend fun rank(key: String, audio: Int, id: String): Int
    @Query("SELECT DISTINCT year FROM version_backfill_rows WHERE resolvedKey=:key AND year!='' LIMIT 2")
    suspend fun groupYears(key: String): List<String>
    @Query("DELETE FROM version_backfill_rows WHERE id IN (SELECT id FROM version_backfill_rows LIMIT :limit)")
    suspend fun clean(limit: Int): Int
    @Query("DELETE FROM version_backfill_rows WHERE source=:source")
    suspend fun discardSource(source: String)
    @Query("""SELECT EXISTS(SELECT 1 FROM version_backfill_state WHERE id=1
        AND (phase IN ('normalize','resolve') OR (phase='apply' AND :id>cursor)))""")
    suspend fun pending(id: String): Boolean
}

/** Normalize first so distant batch boundaries cannot split missing-year/TMDB groups.
 * All sibling probes use the small indexed staging rows, never sealed URLs or full Items.
 */
class VersionBackfill(private val db: LibraryDb, private val source: String? = null,
    private val generation: Long = -1) {
    // Source refresh owns an outer import transaction: cancellation rolls the entire import
    // back, so its bounded phase cursor can stay local. Upgrade cursors remain durable.
    private var refreshState = VersionBackfillState()
    private val work = db.versionBackfill()

    /** True means another batch is needed; cancellation rolls back the cursor with its rows. */
    suspend fun runBatch(limit: Int = 500, timeBudgetMs: Long = Long.MAX_VALUE): Boolean {
        require(limit in 1..500)
        require(timeBudgetMs > 0)
        var measured = false
        var done: VersionBackfillState? = null
        val batchStart = SystemClock.elapsedRealtime()
        val more = db.withTransaction {
            val state = if (source == null) work.state() ?: return@withTransaction false else refreshState
            if (state.phase == "done") return@withTransaction false
            val start = SystemClock.elapsedRealtime()
            var next = state
            when (state.phase) {
                "normalize" -> {
                    val rows = if (source == null) work.candidates(state.cursor, limit)
                        else work.sourceCandidates(source, generation, state.cursor, limit)
                    // Scan the primary key in order, including cheap skips for LIVE/EPISODE.
                    // A kind IN predicate can make old SQLite sort the entire catalog per batch.
                    work.save(rows.filter { it.kind in listOf("MOVIE", "SERIES") }.map { candidate ->
                        val row = candidate.asItem()
                        val name = productionName(row.title, row.year)
                        VersionBackfillRow(row.id, row.source,
                            "${row.source}|${row.kind}|${searchable(name.title)}", name.title, name.year,
                            row.tmdbId(), when (name.version) {
                                AudioVersion.DUBBED -> 0
                                AudioVersion.ORIGINAL -> 1
                                AudioVersion.SUBTITLED -> 2
                            })
                    })
                    next = if (rows.isEmpty()) state.copy(phase = "resolve", cursor = "")
                        else state.copy(cursor = rows.last().id, total = state.total + rows.size)
                }
                "resolve" -> {
                    val rows = if (source == null) work.rows(state.cursor, limit)
                        else work.sourceRows(source, state.cursor, limit)
                    var processed = 0
                    var cursor = state.cursor
                    for (row in rows) {
                        if (processed > 0 && SystemClock.elapsedRealtime() - start >= timeBudgetMs) break
                        val years = work.years(row.bucket)
                        val year = row.year.ifEmpty { years.singleOrNull().orEmpty() }
                        val ids = work.tmdbIds(row.bucket, year, years.size == 1)
                        val tmdb = if (row.title.isBlank()) row.tmdb
                            else if (ids.size <= 1) ids.firstOrNull().orEmpty() else row.tmdb
                        // Provider titles can themselves contain '|'. Only the known source
                        // prefix and media-kind field belong to the TMDB namespace.
                        val kind = row.bucket.removePrefix("${row.source}|").substringBefore('|')
                        val prefix = "${row.source}|$kind"
                        val key = when {
                            tmdb.isNotEmpty() -> "$prefix|tmdb:$tmdb"
                            row.title.isBlank() || ids.size > 1 -> row.id
                            else -> "${row.bucket}|$year"
                        }
                        work.resolve(row.id, key)
                        cursor = row.id
                        processed++
                    }
                    next = if (rows.isEmpty()) state.copy(phase = "apply", cursor = "")
                        else state.copy(cursor = cursor)
                }
                "apply" -> {
                    val rows = if (source == null) work.rows(state.cursor, limit)
                        else work.sourceRows(source, state.cursor, limit)
                    val assignments = ArrayList<VersionAssignment>()
                    var cursor = state.cursor
                    for (row in rows) {
                        if (assignments.isNotEmpty() && SystemClock.elapsedRealtime() - start >= timeBudgetMs) break
                        val rank = work.rank(row.resolvedKey, row.audio, row.id)
                        assignments += VersionAssignment(row.id, row.resolvedKey, rank == 0, row.title,
                            row.year.ifEmpty { work.groupYears(row.resolvedKey).singleOrNull().orEmpty() }, rank)
                        cursor = row.id
                    }
                    db.library().assignVersions(assignments)
                    next = if (rows.isEmpty()) state.copy(phase = "clean", cursor = "")
                        else state.copy(cursor = cursor)
                }
                "clean" -> {
                    val deleted = if (source == null) work.clean(limit) else work.cleanSource(source, limit)
                    if (deleted == 0) next = state.copy(phase = "done")
                }
                else -> error("Unknown version backfill phase")
            }
            next = next.copy(elapsed = state.elapsed + SystemClock.elapsedRealtime() - start)
            if (source == null) work.save(next) else refreshState = next
            measured = true
            if (next.phase == "done") done = next
            next.phase != "done"
        }
        if (measured) {
            // Includes the commit; fixed names never contain source or content identifiers.
            if (source == null) StartupPerf.elapsed("version_backfill_batch", batchStart)
            else StartupPerf.elapsed("refresh_version_batch", batchStart)
        }
        done?.let {
            if (source == null) StartupPerf.completed("version_backfill_done", it.elapsed, it.total)
            else StartupPerf.completed("refresh_version_done", it.elapsed, it.total)
        }
        return more
    }
}
