package dev.lumenara.data

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Only the retained page window crosses into Compose, never a kind per catalog result. */
data class Wp19SpanWindow(val offset: Int, val live: List<Boolean>, val columns: List<Int>)

internal fun wp19ReadSpans(cursor: Cursor, offset: Int, limit: Int, columns: Int,
    checkActive: () -> Unit): Wp19SpanWindow {
    require(offset >= 0 && limit in 1..400 && columns > 0)
    val live = ArrayList<Boolean>(limit)
    val positions = ArrayList<Int>(limit)
    var column = 0
    var index = 0
    // The SQLite cursor streams the prefix to preserve exact mixed-span packing, including
    // a channel wrapping after an odd poster. No prefix List/BooleanArray is allocated.
    while (index.toLong() < offset.toLong() + limit && cursor.moveToNext()) {
        checkActive()
        val channel = cursor.getString(0) == "LIVE"
        val span = if (channel) 2 else 1
        if (column + span > columns) column = 0
        if (index >= offset) { live += channel; positions += column }
        column = (column + span) % columns
        index++
    }
    return Wp19SpanWindow(offset, live, positions)
}

/** Read-only cursor and invalidation tracking keep facade signatures unchanged. */
fun LibraryDao.wp19SpanWindow(source: String, category: String, query: String, shelf: String,
    year: String, genre: String, language: String, minRating: Double, hidden: List<String>,
    offset: Int, limit: Int, columns: Int): Flow<Wp19SpanWindow> {
    require(offset >= 0 && limit in 1..400 && columns > 0)
    return withWp19Search(source, query) { derived, indexed ->
        val filter = CatalogFilter(source = source, category = category, query = query, shelf = shelf,
            year = year, genre = genre, language = language, minRating = minRating, hidden = hidden,
            derived = derived, searchGram = indexed)
        val kinds = browseKindsQuery(filter)
        wp19SpanInvalidations(SimpleSQLiteQuery("SELECT 1")).map {
            val context = currentCoroutineContext()
            // LIMIT applies after identical filtering/grouping/sorting. The prefix is only
            // visited to recover the starting column; Item payloads are never selected.
            val bounded = object : androidx.sqlite.db.SupportSQLiteQuery {
                override val sql = "${kinds.sql} LIMIT ${offset.toLong() + limit}"
                override val argCount = kinds.argCount
                override fun bindTo(statement: androidx.sqlite.db.SupportSQLiteProgram) = kinds.bindTo(statement)
            }
            wp19SpanCursor(bounded).use { cursor ->
                wp19ReadSpans(cursor, offset, limit, columns) { context.ensureActive() }
            }
        }
    }.flowOn(Dispatchers.IO)
}
