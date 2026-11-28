package dev.lumenara.data

import android.os.SystemClock
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** Numeric document IDs avoid repeating the long content ID in every posting. */
@Entity(tableName = "wp19_search_documents", indices = [Index(value = ["itemId"], unique = true)],
    foreignKeys = [ForeignKey(entity = Item::class, parentColumns = ["id"], childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE)])
data class Wp19SearchDocument(@PrimaryKey(autoGenerate = true) val documentId: Long = 0,
    val itemId: String, val search: String)

@Entity(tableName = "wp19_search_grams", primaryKeys = ["gram", "documentId"],
    indices = [Index("documentId")], foreignKeys = [ForeignKey(entity = Wp19SearchDocument::class,
        parentColumns = ["documentId"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)])
data class Wp19SearchGram(val gram: Long, val documentId: Long)

@Entity(tableName = "wp19_search_state")
data class Wp19SearchState(@PrimaryKey val id: Int = 1, val cursor: String = "", val done: Boolean = false)

data class Wp19SearchSeed(val id: String, val search: String)

/** Exact Unicode code-point bigrams, including spaces/punctuation; no extra folding or tokens.
 * Two-character searches use the same index. A single code point uses the existing bounded page.
 * Surrogates must never be split into invalid UTF-8 bind values.
 */
internal fun wp19Grams(search: String): Set<Long> {
    val points = search.codePoints().toArray()
    return buildSet {
        for (i in 0 until points.size - 1) add((points[i].toLong() shl 21) or points[i + 1].toLong())
    }
}

internal fun wp19ProbeGrams(query: String): List<Long> {
    val grams = wp19Grams(query).toList()
    if (grams.size <= 3) return grams
    return listOf(grams.first(), grams[grams.size / 2], grams.last()).distinct()
}

/** Inherited by LibraryDao: every search-text writer and its postings share one transaction. */
@Dao
interface Wp19SearchDao {
    @Query("SELECT * FROM wp19_search_documents WHERE itemId=:id")
    fun wp19Document(id: String): Wp19SearchDocument?
    @Insert fun wp19InsertDocument(row: Wp19SearchDocument): Long
    @Query("DELETE FROM wp19_search_documents WHERE itemId=:id") fun wp19DeleteDocument(id: String)
    @Insert fun wp19InsertGrams(rows: List<Wp19SearchGram>)
    @Query("SELECT search FROM items WHERE id=:id") fun wp19SearchText(id: String): String?
    @Query("SELECT * FROM wp19_search_state WHERE id=1") fun wp19State(): Wp19SearchState?
    @Upsert fun wp19SaveState(state: Wp19SearchState)
    @Query("SELECT EXISTS(SELECT 1 FROM wp19_search_state WHERE id=1 AND done=1)")
    fun wp19SearchReady(): Flow<Boolean>
    // Bounded planner probe: broad words should keep the original order-streaming LIMIT,
    // not sort tens of thousands of candidate primary-key lookups for their first page.
    @Query("SELECT COUNT(*) FROM (SELECT documentId FROM wp19_search_grams WHERE gram=:gram LIMIT 513)")
    suspend fun wp19ProbeSize(gram: Long): Int
    @Query("SELECT id,search FROM items WHERE id>:cursor ORDER BY id LIMIT :limit")
    fun wp19Seeds(cursor: String, limit: Int): List<Wp19SearchSeed>

    @Query("""SELECT NOT EXISTS(SELECT 1 FROM items i LEFT JOIN wp19_search_documents d
        ON d.itemId=i.id WHERE d.itemId IS NULL)""")
    fun wp19AllIndexed(): Boolean

    /** Fresh imports have already indexed every row; do not wait for an upgrade cursor. */
    @Transaction fun wp19PublishIfCovered() {
        if (wp19State()?.done != true && wp19AllIndexed()) wp19SaveState(Wp19SearchState(done = true))
    }

    // Caller owns a Room transaction. Unchanged refreshes do not rewrite postings.
    fun wp19Index(id: String, search: String) {
        if (wp19Document(id)?.search == search) return
        wp19DeleteDocument(id)
        val document = wp19InsertDocument(Wp19SearchDocument(itemId = id, search = search))
        wp19InsertGrams(wp19Grams(search).map { Wp19SearchGram(it, document) })
    }
}

/** At most three capped index probes; either plan has the same final instr predicate.
 * The choice may stay cached for a subscription across imports: this affects cost, never results.
 */
internal suspend fun Wp19SearchDao.wp19SelectiveGram(query: String): Long? {
    var selected: Long? = null
    var smallest = 513
    for (gram in wp19ProbeGrams(query)) {
        val size = wp19ProbeSize(gram)
        if (size < smallest) { selected = gram; smallest = size }
        if (size == 0) break
    }
    return selected
}

/** No row work at database open. Cursor and postings commit together, including an empty tail.
 * New/changed rows behind the cursor are indexed by the transactional LibraryDao writers.
 */
class Wp19SearchBackfill(private val db: LibraryDb) {
    suspend fun runBatch(limit: Int = 16, timeBudgetMs: Long = 20): Boolean {
        require(limit in 1..64 && timeBudgetMs > 0)
        val start = SystemClock.elapsedRealtime()
        return db.withTransaction {
            val dao = db.library()
            val state = dao.wp19State() ?: Wp19SearchState()
            if (state.done) return@withTransaction false
            val rows = dao.wp19Seeds(state.cursor, limit)
            if (rows.isEmpty()) {
                dao.wp19SaveState(state.copy(done = true))
                return@withTransaction false
            }
            var cursor = state.cursor
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                dao.wp19Index(row.id, row.search)
                cursor = row.id
                if (SystemClock.elapsedRealtime() - start >= timeBudgetMs) break
            }
            dao.wp19SaveState(state.copy(cursor = cursor))
            true
        }.also { StartupPerf.duration("wp19_search_backfill_batch", SystemClock.elapsedRealtime() - start) }
    }
}

internal fun wp19CreateSearchTables(db: SupportSQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS wp19_search_documents (documentId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        itemId TEXT NOT NULL, search TEXT NOT NULL, FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_wp19_search_documents_itemId ON wp19_search_documents(itemId)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS wp19_search_grams (gram INTEGER NOT NULL, documentId INTEGER NOT NULL,
        PRIMARY KEY(gram,documentId), FOREIGN KEY(documentId) REFERENCES wp19_search_documents(documentId) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_wp19_search_grams_documentId ON wp19_search_grams(documentId)")
    db.execSQL("CREATE TABLE IF NOT EXISTS wp19_search_state (id INTEGER NOT NULL, cursor TEXT NOT NULL, done INTEGER NOT NULL, PRIMARY KEY(id))")
}
