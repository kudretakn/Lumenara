package dev.lumenara.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.lumenara.catalog.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bump when normalization/grouping rules change. Missing state always takes the full fallback. */
const val WP22_COMPUTATION_VERSION = 1

@Entity(tableName = "wp22_catalog_state", foreignKeys = [ForeignKey(entity = Source::class,
    parentColumns = ["id"], childColumns = ["source"], onDelete = ForeignKey.CASCADE)])
data class Wp22CatalogState(@PrimaryKey val source: String, val version: Int)

/** Small, durable input/normalization cache. No URLs, artwork, synopsis or Item copies. */
@Entity(tableName = "wp22_inputs", foreignKeys = [ForeignKey(entity = Source::class,
    parentColumns = ["id"], childColumns = ["source"], onDelete = ForeignKey.CASCADE)], indices = [Index("source", "id"), Index("bucket", "year"),
    Index("resolvedKey", "audio", "id")])
data class Wp22Input(@PrimaryKey val id: String, val source: String, val kind: String,
    val rawTitle: String, val rawYear: String, val rawSearch: String, val category: String,
    val bucket: String, val title: String, val year: String, val tmdb: String, val audio: Int,
    val resolvedKey: String = "") {
    fun matches(row: VersionCandidate) = source == row.source && kind == row.kind &&
        rawTitle == row.title && rawYear == row.year && rawSearch == row.search && category == row.category
}

@Entity(tableName = "wp22_dirty", indices = [Index("source")], foreignKeys = [ForeignKey(
    entity = Item::class, parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE)])
data class Wp22Dirty(@PrimaryKey val id: String, val source: String)

@Dao
interface Wp22Dao {
    @Query("SELECT * FROM wp22_catalog_state WHERE source=:source") fun wp22State(source: String): Wp22CatalogState?
    @Upsert fun wp22SaveState(row: Wp22CatalogState)
    @Query("SELECT * FROM wp22_inputs WHERE id=:id") fun wp22Input(id: String): Wp22Input?
    @Upsert fun wp22SaveInput(row: Wp22Input)
    @Query("DELETE FROM wp22_inputs WHERE id=:id") fun wp22DeleteInput(id: String)
    @Query("SELECT * FROM wp22_inputs WHERE source=:source AND id>:cursor ORDER BY id LIMIT 250")
    fun wp22Inputs(source: String, cursor: String): List<Wp22Input>
    @Query("""SELECT id,source,title,kind,search,year,category FROM items
        WHERE source=:source AND id>:cursor AND kind IN ('LIVE','MOVIE','SERIES')
        AND (:generation<0 OR generation=:generation OR kind NOT IN ('MOVIE','SERIES')) ORDER BY id LIMIT 250""")
    fun wp22Candidates(source: String, cursor: String, generation: Long): List<VersionCandidate>
    @Query("""SELECT i.id,i.source,i.title,i.kind,i.search,i.year,i.category FROM wp22_dirty d
        JOIN items i ON i.id=d.id WHERE d.source=:source ORDER BY d.id LIMIT 250""")
    fun wp22DirtyCandidates(source: String): List<VersionCandidate>
    @Query("INSERT OR IGNORE INTO wp22_dirty SELECT id,source FROM items WHERE id=:id") fun wp22Dirty(id: String)
    @Query("DELETE FROM wp22_dirty WHERE id=:id") fun wp22Clean(id: String)
    @Query("DELETE FROM wp22_dirty WHERE source=:source") fun wp22CleanSource(source: String)
    @Query("SELECT EXISTS(SELECT 1 FROM wp22_dirty WHERE source=:source)") fun wp22HasDirty(source: String): Boolean
    @Query("SELECT * FROM wp22_inputs WHERE bucket=:bucket AND id>:cursor ORDER BY id LIMIT 250")
    fun wp22Bucket(bucket: String, cursor: String): List<Wp22Input>
    @Query("SELECT DISTINCT year FROM wp22_inputs WHERE bucket=:bucket AND year!='' LIMIT 2")
    fun wp22Years(bucket: String): List<String>
    @Query("""SELECT DISTINCT tmdb FROM wp22_inputs WHERE bucket=:bucket AND tmdb!=''
        AND (year=:year OR (:infer=1 AND year='')) LIMIT 2""")
    fun wp22Tmdb(bucket: String, year: String, infer: Boolean): List<String>
    @Query("UPDATE wp22_inputs SET resolvedKey=:key WHERE id=:id") fun wp22Resolve(id: String, key: String)
    @Query("SELECT * FROM wp22_inputs WHERE resolvedKey=:key ORDER BY audio,id")
    fun wp22Group(key: String): List<Wp22Input>
    @Query("""SELECT EXISTS(SELECT 1 FROM items WHERE id=:id AND
        (:generation<0 OR generation=:generation OR kind NOT IN ('MOVIE','SERIES')))""")
    fun wp22Eligible(id: String, generation: Long): Boolean
}

internal fun wp22Normalize(row: VersionCandidate): Wp22Input {
    val movie = row.kind == "MOVIE" || row.kind == "SERIES"
    val name = if (movie) productionName(row.title, row.year) else null
    return Wp22Input(row.id, row.source, row.kind, row.title, row.year, row.search, row.category,
        if (movie) "${row.source}|${row.kind}|${searchable(checkNotNull(name).title)}" else "",
        name?.title.orEmpty(), name?.year.orEmpty(), if (movie) row.asItem().tmdbId() else "",
        when (name?.version) { AudioVersion.DUBBED -> 0; AudioVersion.SUBTITLED -> 2; else -> 1 })
}

/** One import transaction owns cache, output and publication. A crash rolls back all three.
 * An unchanged digest visits only the durable metadata queue, never the catalog input table.
 */
class Wp22Incremental(private val db: LibraryDb) {
    suspend fun prepare(source: String, bodyChanged: Boolean, generation: Long = -1): Wp22Work = db.withTransaction {
        val dao = db.library()
        val full = dao.wp22State(source)?.version != WP22_COMPUTATION_VERSION ||
            db.derived().state(source)?.phase != "done"
        if (!full && !bodyChanged && !dao.wp22HasDirty(source)) return@withTransaction Wp22Work()
        val versionStart = android.os.SystemClock.elapsedRealtime()
        val buckets = linkedSetOf<String>()
        val keys = linkedSetOf<String>()
        val categories = linkedSetOf<Pair<String, String>>()
        var liveChanged = false
        var normalized = 0
        var resolved = 0
        var applied = 0
        fun affect(row: Wp22Input) {
            if (row.bucket.isNotEmpty()) buckets += row.bucket
            if (row.resolvedKey.isNotEmpty()) keys += row.resolvedKey
            if (row.kind in listOf("LIVE", "MOVIE", "SERIES")) categories += row.kind to row.category
            if (row.kind == "LIVE") liveChanged = true
        }
        fun accept(row: VersionCandidate) {
            if (row.kind !in listOf("LIVE", "MOVIE", "SERIES")) {
                dao.wp22Clean(row.id)
                return
            }
            val old = dao.wp22Input(row.id)
            if (full || old == null || !old.matches(row)) {
                old?.let(::affect)
                val next = wp22Normalize(row)
                affect(next)
                dao.wp22SaveInput(next)
                if (next.bucket.isNotEmpty()) normalized++
            }
            if (!bodyChanged && !full) dao.wp22Clean(row.id)
        }
        if (bodyChanged || full) {
            var cursor = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val rows = dao.wp22Candidates(source, cursor, generation)
                if (rows.isEmpty()) break
                rows.forEach(::accept)
                cursor = rows.last().id
            }
            // Deletions retain their old grouping identity until the new publication succeeds.
            cursor = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val rows = dao.wp22Inputs(source, cursor)
                if (rows.isEmpty()) break
                rows.filterNot { dao.wp22Eligible(it.id, generation) }.forEach { affect(it); dao.wp22DeleteInput(it.id) }
                cursor = rows.last().id
            }
        } else {
            while (true) {
                currentCoroutineContext().ensureActive()
                val rows = dao.wp22DirtyCandidates(source)
                if (rows.isEmpty()) break
                rows.forEach(::accept)
            }
        }
        // First resolve every affected title bucket, then rank both old and new TMDB groups.
        // This covers missing-year inference, conflicting remakes and translated titles.
        for (bucket in buckets) {
            val years = dao.wp22Years(bucket)
            var cursor = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val rows = dao.wp22Bucket(bucket, cursor)
                if (rows.isEmpty()) break
                for (row in rows) {
                    val year = row.year.ifEmpty { years.singleOrNull().orEmpty() }
                    val ids = dao.wp22Tmdb(bucket, year, years.size == 1)
                    val tmdb = if (row.title.isBlank()) row.tmdb
                        else if (ids.size <= 1) ids.firstOrNull().orEmpty() else row.tmdb
                    val key = when {
                        tmdb.isNotEmpty() -> "${row.source}|${row.kind}|tmdb:$tmdb"
                        row.title.isBlank() || ids.size > 1 -> row.id
                        else -> "${row.bucket}|$year"
                    }
                    if (row.resolvedKey.isNotEmpty()) keys += row.resolvedKey
                    keys += key
                    dao.wp22Resolve(row.id, key)
                    resolved++
                }
                cursor = rows.last().id
            }
        }
        val publishedKeys = ArrayList<String>()
        for (key in keys) {
            currentCoroutineContext().ensureActive()
            val rows = dao.wp22Group(key)
            val years = rows.map { it.year }.filter { it.isNotEmpty() }.distinct()
            db.library().assignVersions(rows.mapIndexed { rank, row ->
                categories += row.kind to row.category
                VersionAssignment(row.id, key, rank == 0, row.title,
                    row.year.ifEmpty { years.singleOrNull().orEmpty() }, rank)
            })
            applied += rows.size
            if (rows.isNotEmpty()) publishedKeys += key
        }
        // Every assignment must be visible before sharing marks. Otherwise a member leaving
        // an old TMDB group could donate its progress to that group's remaining members.
        for (key in publishedKeys) {
            currentCoroutineContext().ensureActive()
            val marks = dao.versionGroupMarks(key)
            val newest = marks.maxByOrNull { it.updated }
            if (newest != null) {
                val shared = newest.copy(favorite = marks.any { it.favorite }, listed = marks.any { it.listed })
                dao.versionGroupIds(key).forEach { dao.save(shared.copy(id = it)) }
            }
        }
        StartupPerf.elapsed("refresh_version_groups", versionStart)
        val derivedStart = android.os.SystemClock.elapsedRealtime()
        val derived = CatalogDerivedBackfill(db)
        if (full) {
            derived.reset(source)
            while (derived.runBatch(source)) { currentCoroutineContext().ensureActive() }
        } else {
            derived.refreshCategories(source, categories)
            if (liveChanged) derived.refreshChannels(source)
        }
        StartupPerf.elapsed("refresh_catalog_derived", derivedStart)
        if (bodyChanged || full) dao.wp22CleanSource(source)
        dao.wp22SaveState(Wp22CatalogState(source, WP22_COMPUTATION_VERSION))
        dao.wp19PublishIfCovered()
        Wp22Work(normalized, resolved, applied, categories.size)
    }
}

data class Wp22Work(val normalized: Int = 0, val resolved: Int = 0, val applied: Int = 0,
    val categories: Int = 0)

internal fun wp22CreateTables(db: SupportSQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS wp22_catalog_state (source TEXT NOT NULL, version INTEGER NOT NULL,
        PRIMARY KEY(source), FOREIGN KEY(source) REFERENCES sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    db.execSQL("""CREATE TABLE IF NOT EXISTS wp22_inputs (id TEXT NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL,
        rawTitle TEXT NOT NULL, rawYear TEXT NOT NULL, rawSearch TEXT NOT NULL, category TEXT NOT NULL,
        bucket TEXT NOT NULL, title TEXT NOT NULL, year TEXT NOT NULL, tmdb TEXT NOT NULL, audio INTEGER NOT NULL,
        resolvedKey TEXT NOT NULL, PRIMARY KEY(id),
        FOREIGN KEY(source) REFERENCES sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_wp22_inputs_source_id ON wp22_inputs(source,id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_wp22_inputs_bucket_year ON wp22_inputs(bucket,year)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_wp22_inputs_resolvedKey_audio_id ON wp22_inputs(resolvedKey,audio,id)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS wp22_dirty (id TEXT NOT NULL, source TEXT NOT NULL, PRIMARY KEY(id),
        FOREIGN KEY(id) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_wp22_dirty_source ON wp22_dirty(source)")
}
