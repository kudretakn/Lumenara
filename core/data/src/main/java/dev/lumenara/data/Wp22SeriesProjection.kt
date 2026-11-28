package dev.lumenara.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.lumenara.catalog.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class Wp22SeriesLocator(val id: String, val title: String, val encryptedUrl: String)
internal data class Wp22EpisodeChoice(val season: Int, val episode: Int, val duration: Long) {
    fun precedes(old: Wp22EpisodeChoice?): Boolean = old == null || season < old.season ||
        season == old.season && episode < old.episode
}

@Entity(tableName = "wp22_projection_state")
data class Wp22ProjectionState(@PrimaryKey val id: Int = 1, val version: Int = 1,
    val cursor: String = "", val done: Boolean = false)

data class Wp22EpisodeSeed(val id: String, val title: String, val category: String)
data class Wp22EpisodeAssignment(val id: String, val episodeSeason: Int, val episodeNumber: Int,
    val episodeSeries: String, val episodeTitle: String, val episodeVersion: Int = 1)

internal fun wp22EpisodeAssignment(id: String, title: String, category: String): Wp22EpisodeAssignment {
    val name = episodeName(title, category)
    return Wp22EpisodeAssignment(id, name.season, name.episode, name.series, name.title.displayTitle())
}

internal fun Item.wp22ProjectEpisode(): Item {
    if (kind != "EPISODE") return this
    val row = wp22EpisodeAssignment(id, title, category)
    return copy(episodeSeason = row.episodeSeason, episodeNumber = row.episodeNumber,
        episodeSeries = row.episodeSeries, episodeTitle = row.episodeTitle, episodeVersion = row.episodeVersion)
}

fun Item.wp22EpisodeName(): EpisodeName = if (episodeVersion == 1)
    EpisodeName(episodeSeries, episodeSeason, episodeNumber, episodeTitle) else episodeName(title, category)

@Dao
interface Wp22SeriesDao {
    @Query("SELECT * FROM wp22_projection_state WHERE id=1") fun wp22ProjectionState(): Wp22ProjectionState?
    @Upsert fun wp22ProjectionState(state: Wp22ProjectionState)
    @Query("""SELECT id,title,category FROM items WHERE id>:cursor AND kind='EPISODE'
        AND episodeVersion!=1 ORDER BY id LIMIT :limit""")
    suspend fun wp22EpisodeSeeds(cursor: String, limit: Int): List<Wp22EpisodeSeed>
    @androidx.room.Update(entity = Item::class) suspend fun wp22Project(rows: List<Wp22EpisodeAssignment>)
}

/** Additive upgrade: bounded work/cursor commits together; old rows keep the WP14 fallback. */
class Wp22SeriesBackfill(private val db: LibraryDb) {
    suspend fun runBatch(limit: Int = 100): Boolean = db.withTransaction {
        require(limit in 1..250)
        val dao = db.library()
        val stored = dao.wp22ProjectionState()
        val state = stored?.takeIf { it.version == 1 } ?: Wp22ProjectionState()
        if (state.done) return@withTransaction false
        val rows = dao.wp22EpisodeSeeds(state.cursor, limit)
        dao.wp22Project(rows.map { wp22EpisodeAssignment(it.id, it.title, it.category) })
        dao.wp22ProjectionState(state.copy(cursor = rows.lastOrNull()?.id ?: state.cursor, done = rows.isEmpty()))
        rows.isNotEmpty()
    }
}

/** Room already returns season/episode/id order. Ready data requires no regex or sort. */
suspend fun wp22SeriesCatalog(items: List<Item>): Wp14SeriesCatalog = withContext(Dispatchers.Default) {
    if (items.any { it.episodeVersion != 1 }) return@withContext wp14SeriesCatalog(items)
    val names = items.associate { it.id to it.wp22EpisodeName() }
    Wp14SeriesCatalog(items, names, items.associate { it.id to it.episodeTitle },
        items.groupBy { it.episodeSeason }, items.mapIndexed { index, item -> item.id to index }.toMap())
}

/** Same tie breaks and 90% boundary as continueEpisode, in one pass over stored order. */
suspend fun wp22SeriesProgress(catalog: Wp14SeriesCatalog, marks: List<Mark>): Wp14SeriesProgress =
    withContext(Dispatchers.Default) {
        val byId = marks.associateBy { it.id }
        var unfinished: Item? = null
        var latest: Item? = null
        var latestIndex = -1
        var firstUnwatched: Item? = null
        val completed = catalog.seasons.keys.toMutableSet()
        fun watchedItem(item: Item) = byId[item.id]?.let { watched(it.position, it.duration) } == true
        catalog.items.forEachIndexed { index, item ->
            val mark = byId[item.id]
            val name = catalog.names.getValue(item.id)
            if (!watchedItem(item)) {
                if (firstUnwatched == null) firstUnwatched = item
                completed.remove(name.season)
            }
            if (mark != null && mark.position > 0) {
                val old = unfinished?.let { byId[it.id] }
                if (!watchedItem(item) && (old == null || mark.updated > old.updated ||
                        mark.updated == old.updated && item.id > old.id)) unfinished = item
                val previous = latest
                val previousMark = previous?.let { byId[it.id] }
                val previousName = previous?.let { catalog.names.getValue(it.id) }
                if (previousMark == null || mark.updated > previousMark.updated ||
                    mark.updated == previousMark.updated && previousName != null &&
                    (name.season > previousName.season || name.season == previousName.season && name.episode > previousName.episode)) {
                    latest = item
                    latestIndex = index
                }
            }
        }
        val after = if (latestIndex >= 0) (latestIndex + 1 until catalog.items.size)
            .firstOrNull { !watchedItem(catalog.items[it]) }?.let { catalog.items[it] } else null
        Wp14SeriesProgress(byId, unfinished ?: after ?: firstUnwatched ?: catalog.items.firstOrNull(),
            marks.any { it.position > 0 }, completed)
    }

internal fun wp22CreateProjection(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE items ADD COLUMN episodeSeason INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE items ADD COLUMN episodeNumber INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE items ADD COLUMN episodeSeries TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE items ADD COLUMN episodeTitle TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE items ADD COLUMN episodeVersion INTEGER NOT NULL DEFAULT 0")
    db.execSQL("CREATE INDEX index_items_parent_episodeSeason_episodeNumber_id ON items(parent,episodeSeason,episodeNumber,id)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS wp22_projection_state (id INTEGER NOT NULL, version INTEGER NOT NULL,
        cursor TEXT NOT NULL, done INTEGER NOT NULL, PRIMARY KEY(id))""")
}
