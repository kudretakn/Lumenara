package dev.lumenara.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "sources")
data class Source(
    @PrimaryKey val id: String,
    val name: String,
    val endpoint: String,
    val allowHttp: Boolean,
    val refreshed: Long,
    val count: Int,
)

@Entity(
    tableName = "items",
    foreignKeys =
        [
            ForeignKey(
                entity = Source::class,
                parentColumns = ["id"],
                childColumns = ["source"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("source"), Index("kind", "title")],
)
data class Item(
    @PrimaryKey val id: String,
    val source: String,
    val title: String,
    val category: String,
    val kind: String,
    val tvgId: String,
    val encryptedUrl: String,
    val search: String,
    val generation: Long,
    val logo: String,
    val parent: String = "",
    @ColumnInfo(defaultValue = "''") val summary: String = "",
    @ColumnInfo(defaultValue = "''") val year: String = "",
    @ColumnInfo(defaultValue = "''") val genre: String = "",
    @ColumnInfo(defaultValue = "''") val rating: String = "",
    @ColumnInfo(defaultValue = "''") val language: String = "",
    @ColumnInfo(defaultValue = "''") val castNames: String = "",
    @ColumnInfo(defaultValue = "''") val archive: String = "",
    @ColumnInfo(defaultValue = "0") val archiveDays: Int = 0,
    @ColumnInfo(defaultValue = "''") val archiveZone: String = "",
    @ColumnInfo(defaultValue = "-1") val introStartMs: Long = -1,
    @ColumnInfo(defaultValue = "-1") val introEndMs: Long = -1,
    @ColumnInfo(defaultValue = "''") val backdrop: String = "",
)

@Entity(
    tableName = "marks",
    foreignKeys =
        [
            ForeignKey(
                entity = Item::class,
                parentColumns = ["id"],
                childColumns = ["id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class Mark(
    @PrimaryKey val id: String,
    val favorite: Boolean = false,
    val listed: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val updated: Long = 0,
)

@Entity(
    tableName = "collections",
    indices = [Index("name")],
    foreignKeys =
        [
            ForeignKey(
                entity = Item::class,
                parentColumns = ["id"],
                childColumns = ["id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class Member(@PrimaryKey val id: String, val name: String)

data class LiveCategoryCount(val category: String, val count: Int)

/** Lightweight projection; this is not a persisted entity or schema change. */
data class LiveChannelId(val id: String, val source: String)

@Dao
interface LibraryDao {
    @Query("""SELECT id,source FROM items WHERE kind='LIVE' AND (:source='' OR source=:source)
        ORDER BY source,title,id""")
    fun liveChannelOrder(source: String): Flow<List<LiveChannelId>>

    @Query("""SELECT * FROM items WHERE id IN (:ids) AND kind='LIVE'
        AND (:source='' OR source=:source)""")
    fun liveByIds(source: String, ids: List<String>): Flow<List<Item>>

    @Query("""SELECT * FROM items WHERE (:source='' OR source=:source)
        AND kind IN ('MOVIE','SERIES') AND (backdrop!='' OR logo!='')
        ORDER BY items.rowid DESC""")
    fun homeFeaturesRows(source: String): Flow<List<Item>>

    fun homeFeatures(source: String): Flow<List<Item>> = homeFeaturesRows(source).map { it.mergeVersions().take(6) }

    @Query("""SELECT * FROM items WHERE kind='LIVE' AND (:source='' OR source=:source)
        AND category=(SELECT category FROM items WHERE kind='LIVE'
            AND (:source='' OR source=:source) ORDER BY category,title,id LIMIT 1)
        ORDER BY title,id LIMIT 20""")
    fun firstLiveCategory(source: String): Flow<List<Item>>

    @Query("""SELECT category, COUNT(*) AS count FROM items
        WHERE kind='LIVE' AND (:source='' OR source=:source) GROUP BY category ORDER BY category""")
    fun liveCategoryCounts(source: String): Flow<List<LiveCategoryCount>>

    @Upsert suspend fun collection(member: Member)

    @Query(
        """SELECT DISTINCT name FROM collections JOIN items USING(id)
        WHERE (:source='' OR source=:source) ORDER BY name LIMIT 20"""
    )
    fun collections(source: String): Flow<List<String>>

    @Query(
        """SELECT items.* FROM items JOIN collections USING(id) WHERE name=:name AND (:source='' OR source=:source)
        ORDER BY year,title"""
    )
    fun membersRows(source: String, name: String): Flow<List<Item>>

    fun members(source: String, name: String): Flow<List<Item>> = membersRows(source, name).map { it.mergeVersions().take(60) }

    @Query(
        """SELECT i.* FROM items i WHERE (:source='' OR i.source=:source) AND i.kind IN ('MOVIE','SERIES')
        AND i.category IN (SELECT j.category FROM items j JOIN marks m ON m.id=j.id WHERE m.favorite=1 OR m.position>0)
        AND i.id NOT IN (SELECT id FROM marks WHERE position>0 OR favorite=1) ORDER BY CAST(i.rating AS REAL) DESC,i.title"""
    )
    fun suggestionsRows(source: String): Flow<List<Item>>

    fun suggestions(source: String): Flow<List<Item>> = suggestionsRows(source).map { it.mergeVersions().take(24) }

    @Query("SELECT * FROM sources ORDER BY name, id") fun sources(): Flow<List<Source>>

    @Query("SELECT * FROM sources ORDER BY id") suspend fun snapshot(): List<Source>

    @Query("SELECT * FROM sources WHERE id=:id") suspend fun source(id: String): Source?

    @Upsert suspend fun save(source: Source)

    @Upsert suspend fun save(items: List<Item>)

    @Upsert fun writeBatch(items: List<Item>)

    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE id=:id)")
    suspend fun exists(id: String): Boolean

    @Upsert suspend fun save(mark: Mark)

    @Query("SELECT * FROM marks WHERE id=:id") suspend fun mark(id: String): Mark?

    @Query("SELECT * FROM marks WHERE id=:id") fun observeMark(id: String): Flow<Mark?>

    @Query("SELECT * FROM items WHERE parent=:id ORDER BY items.rowid")
    fun episodes(id: String): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE parent=:id")
    suspend fun episodeSnapshot(id: String): List<Item>

    @Query("SELECT marks.* FROM marks JOIN items USING(id) WHERE items.parent=:id")
    fun episodeMarks(id: String): Flow<List<Mark>>

    @Query("""SELECT i.* FROM items i JOIN (
        SELECT CASE WHEN ep.kind='EPISODE' THEN ep.parent ELSE ep.id END AS cardId, MAX(m.updated) AS activity
        FROM items ep JOIN marks m ON m.id=ep.id WHERE m.position>0 AND
            (ep.kind='EPISODE' OR ep.kind='MOVIE' AND (m.duration<=0 OR m.position<m.duration*0.95))
        GROUP BY cardId
        ) history ON history.cardId=i.id
        WHERE (:source='' OR i.source=:source) AND i.kind IN ('MOVIE','SERIES')
        AND (i.kind!='SERIES' OR EXISTS(SELECT 1 FROM items ep LEFT JOIN marks m ON m.id=ep.id
            WHERE ep.parent=i.id AND (m.id IS NULL OR m.duration<=0 OR m.position<m.duration*0.90)))
        ORDER BY history.activity DESC,i.id""")
    fun continueWatchingRows(source: String): Flow<List<Item>>

    fun continueWatching(source: String): Flow<List<Item>> = continueWatchingRows(source).map { it.mergeVersions().take(24) }

    @Query("DELETE FROM items WHERE parent=:id AND generation!=:token")
    suspend fun pruneEpisodes(id: String, token: Long)

    @Query("DELETE FROM items WHERE source=:id AND parent='' AND generation != :generation")
    suspend fun prune(id: String, generation: Long)

    @Query(
        "DELETE FROM items WHERE source=:id AND parent!='' AND parent NOT IN (SELECT id FROM items WHERE source=:id AND parent='')"
    )
    suspend fun orphans(id: String)

    @Query("DELETE FROM items WHERE source=:id AND generation!=:generation")
    suspend fun prunePlaylist(id: String, generation: Long)

    @Query("DELETE FROM sources WHERE id=:id") suspend fun deleteSource(id: String)

    @Query("SELECT COUNT(*) FROM items WHERE source=:source") suspend fun count(source: String): Int

    @Query(
        """SELECT items.* FROM items LEFT JOIN marks USING(id)
        WHERE (:source = '' OR source=:source) AND kind!='EPISODE' AND (:kind = '' OR kind=:kind)
        AND (:category = '' OR category=:category) AND instr(search,:query)>0
        AND (:year='' OR year=:year) AND (:genre='' OR instr(genre,:genre)>0)
        AND (:language='' OR language=:language) AND CAST(rating AS REAL)>=:minRating
        AND (:shelf != 'favorite' OR favorite=1) AND (:shelf != 'list' OR listed=1)
        AND (:shelf != 'resume' OR position>0 AND (duration<=0 OR position<duration*0.95))
        AND (:shelf != 'popular' OR CAST(rating AS REAL)>0)
        GROUP BY CASE WHEN :source='' AND tvgId!='' AND :shelf NOT IN ('resume','favorite','list')
            THEN substr(items.id,instr(items.id,':')+1) ELSE items.id END,tvgId,title,kind
        HAVING items.id=MIN(items.id)
        ORDER BY CASE WHEN :shelf='resume' THEN updated WHEN :shelf='recent' THEN items.rowid ELSE 0 END DESC,
        CASE WHEN :shelf='popular' THEN CAST(rating AS REAL) ELSE 0 END DESC, title, items.id LIMIT :queryLimit"""
    )
    fun browseRows(
        source: String,
        kind: String,
        category: String,
        query: String,
        shelf: String,
        queryLimit: Int,
        year: String = "",
        genre: String = "",
        language: String = "",
        minRating: Double = 0.0,
    ): Flow<List<Item>>

    fun browse(source: String, kind: String, category: String, query: String, shelf: String, limit: Int,
               year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0): Flow<List<Item>> =
        browseRows(source, kind, category, query, shelf, if (kind == "LIVE") limit else -1, year, genre, language, minRating)
            .map { it.mergeVersions().take(limit) }

    @Query("SELECT * FROM items WHERE id=:id")
    suspend fun item(id: String): Item?

    @Query("SELECT * FROM items WHERE source=:source AND kind=:kind")
    suspend fun versionCandidates(source: String, kind: String): List<Item>

    @Query(
        "SELECT DISTINCT category FROM items WHERE kind=:kind AND (:source='' OR source=:source) ORDER BY category"
    )
    fun categories(kind: String, source: String): Flow<List<String>>
}

@Database(
    entities =
        [
            Source::class,
            Item::class,
            Mark::class,
            Feed::class,
            Slot::class,
            GuideName::class,
            Mapping::class,
            Member::class,
        ],
    version = 7,
    exportSchema = true,
    autoMigrations =
        [
            AutoMigration(from = 1, to = 2),
            AutoMigration(from = 2, to = 3),
            AutoMigration(from = 3, to = 4),
            AutoMigration(from = 4, to = 5),
            AutoMigration(from = 5, to = 6),
            AutoMigration(from = 6, to = 7),
        ],
)
abstract class LibraryDb : RoomDatabase() {
    abstract fun library(): LibraryDao

    abstract fun guide(): GuideDao
}
