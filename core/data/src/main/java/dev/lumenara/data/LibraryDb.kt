package dev.lumenara.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
        AND (:source='' OR source=:source) LIMIT 20""")
    fun liveByIds(source: String, ids: List<String>): Flow<List<Item>>

    @Query("""SELECT * FROM items WHERE (:source='' OR source=:source)
        AND kind IN ('MOVIE','SERIES') AND (backdrop!='' OR logo!='')
        ORDER BY items.rowid DESC LIMIT 6""")
    fun homeFeatures(source: String): Flow<List<Item>>

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
        ORDER BY year,title LIMIT 60"""
    )
    fun members(source: String, name: String): Flow<List<Item>>

    @Query(
        """SELECT i.* FROM items i WHERE (:source='' OR i.source=:source) AND i.kind IN ('MOVIE','SERIES')
        AND i.category IN (SELECT j.category FROM items j JOIN marks m ON m.id=j.id WHERE m.favorite=1 OR m.position>0)
        AND i.id NOT IN (SELECT id FROM marks WHERE position>0 OR favorite=1) ORDER BY CAST(i.rating AS REAL) DESC,i.title LIMIT 24"""
    )
    fun suggestions(source: String): Flow<List<Item>>

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

    @Query("DELETE FROM items WHERE parent=:id AND generation!=:token")
    suspend fun pruneEpisodes(id: String, token: Long)

    @Query("DELETE FROM items WHERE source=:id AND parent='' AND generation != :generation")
    suspend fun prune(id: String, generation: Long)

    @Query(
        "DELETE FROM items WHERE source=:id AND parent!='' AND parent NOT IN (SELECT id FROM items WHERE source=:id AND parent='')"
    )
    suspend fun orphans(id: String)

    @Query("DELETE FROM sources WHERE id=:id") suspend fun deleteSource(id: String)

    @Query("SELECT COUNT(*) FROM items WHERE source=:source") suspend fun count(source: String): Int

    @Query(
        """SELECT items.* FROM items LEFT JOIN marks USING(id)
        WHERE (:source = '' OR source=:source) AND (:kind = '' OR kind=:kind OR :kind='SERIES' AND kind='EPISODE' AND parent='')
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
        CASE WHEN :shelf='popular' THEN CAST(rating AS REAL) ELSE 0 END DESC, title, items.id LIMIT :limit"""
    )
    fun browse(
        source: String,
        kind: String,
        category: String,
        query: String,
        shelf: String,
        limit: Int,
        year: String = "",
        genre: String = "",
        language: String = "",
        minRating: Double = 0.0,
    ): Flow<List<Item>>

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
