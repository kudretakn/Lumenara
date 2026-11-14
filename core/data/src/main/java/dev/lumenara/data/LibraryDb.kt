package dev.lumenara.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
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
    // EQP: source/kind/category equality leaves title ordered; source alone also covers the FK.
    // kind/representative/title serves the unfiltered movie/series grid. Keep kind/title for
    // filtered fallback: the representative OR cannot constrain that index's middle column.
    // parent serves episodes, progress and orphan checks; versionKey serves bounded sibling probes.
    // No tvgId index: guide resolution is in Kotlin and multi-source LIVE dedup groups by more than tvgId.
    indices = [Index("source", "kind", "category", "title"),
        Index("kind", "isRepresentative", "title"), Index("kind", "title"),
        Index("parent"), Index("versionKey")],
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
    @ColumnInfo(defaultValue = "''") val versionKey: String = "",
    @ColumnInfo(defaultValue = "1") val isRepresentative: Boolean = true,
    @ColumnInfo(defaultValue = "''") val displayTitle: String = "",
    @ColumnInfo(defaultValue = "''") val displayYear: String = "",
    // Preferred order within a group permits a filtered/hidden representative to fall back in SQL.
    @ColumnInfo(defaultValue = "0") val versionRank: Int = 0,
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
    @RawQuery(observedEntities = [Item::class, Mark::class, Member::class])
    fun catalogRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [Item::class, Mark::class])
    fun catalogCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [Item::class])
    fun catalogCategoryCounts(query: SupportSQLiteQuery): Flow<List<LiveCategoryCount>>

    @RawQuery(observedEntities = [Item::class])
    fun catalogCategories(query: SupportSQLiteQuery): Flow<List<String>>

    @Query("""SELECT id,source FROM items WHERE kind='LIVE' AND category NOT IN (:hidden) AND (:source='' OR source=:source)
        ORDER BY source,title,id""")
    fun liveChannelOrder(source: String, hidden: List<String> = emptyList()): Flow<List<LiveChannelId>>

    @Query("""SELECT * FROM items WHERE id IN (:ids) AND kind='LIVE'
        AND category NOT IN (:hidden) AND (:source='' OR source=:source)""")
    fun liveByIds(source: String, ids: List<String>, hidden: List<String> = emptyList()): Flow<List<Item>>

    fun homeFeaturesRows(source: String, hidden: List<String> = emptyList()): Flow<List<Item>> =
        catalogRows(shelfRowsQuery(CatalogFilter(source = source, hidden = hidden), 6, "i.rowid DESC") {
            CatalogPredicate("$it.kind IN ('MOVIE','SERIES') AND ($it.backdrop!='' OR $it.logo!='')")
        })

    fun homeFeatures(source: String, hidden: List<String> = emptyList()): Flow<List<Item>> =
        homeFeaturesRows(source, hidden).map { rows -> rows.map(Item::displayVersion) }

    @Query("""SELECT * FROM items WHERE kind='LIVE' AND category NOT IN (:hidden) AND (:source='' OR source=:source)
        AND category=(SELECT category FROM items WHERE kind='LIVE' AND category NOT IN (:hidden)
            AND (:source='' OR source=:source) ORDER BY category,title,id LIMIT 1)
        ORDER BY title,id LIMIT 20""")
    fun firstLiveCategory(source: String, hidden: List<String> = emptyList()): Flow<List<Item>>

    @Query("""SELECT category, COUNT(*) AS count FROM items
        WHERE kind='LIVE' AND category NOT IN (:hidden) AND (:source='' OR source=:source) GROUP BY category ORDER BY category""")
    fun liveCategoryCounts(source: String, hidden: List<String> = emptyList()): Flow<List<LiveCategoryCount>>

    fun categoryCounts(kind: String, source: String, hidden: List<String> = emptyList()): Flow<List<LiveCategoryCount>> =
        catalogCategoryCounts(categoryCountsQuery(CatalogFilter(source = source, kind = kind, hidden = hidden)))

    fun shelfCount(source: String, kind: String, shelf: String, hidden: List<String> = emptyList()): Flow<Int> =
        catalogCount(shelfCountQuery(CatalogFilter(source = source, kind = kind,
            shelf = shelf.takeIf { it in listOf("favorite", "list") }.orEmpty(), hidden = hidden)))

    @Upsert suspend fun collection(member: Member)

    @Query(
        """SELECT DISTINCT name FROM collections JOIN items USING(id)
        WHERE category NOT IN (:hidden) AND (:source='' OR source=:source) ORDER BY name LIMIT 20"""
    )
    fun collections(source: String, hidden: List<String> = emptyList()): Flow<List<String>>

    fun membersRows(source: String, name: String, hidden: List<String> = emptyList()): Flow<List<Item>> =
        catalogRows(shelfRowsQuery(CatalogFilter(source = source, hidden = hidden), 60, "i.year,i.title") {
            CatalogPredicate("EXISTS(SELECT 1 FROM collections WHERE id=$it.id AND name=?)", listOf(name))
        })

    fun members(source: String, name: String, hidden: List<String> = emptyList()): Flow<List<Item>> =
        membersRows(source, name, hidden).map { rows -> rows.map(Item::displayVersion) }

    fun suggestionsRows(source: String, hidden: List<String> = emptyList()): Flow<List<Item>> =
        catalogRows(shelfRowsQuery(CatalogFilter(source = source, hidden = hidden), 24, "CAST(i.rating AS REAL) DESC,i.title") {
            CatalogPredicate("""$it.kind IN ('MOVIE','SERIES')
                AND $it.category IN (SELECT j.category FROM items j JOIN marks m ON m.id=j.id WHERE m.favorite=1 OR m.position>0)
                AND $it.id NOT IN (SELECT id FROM marks WHERE position>0 OR favorite=1)""")
        })

    fun suggestions(source: String, hidden: List<String> = emptyList()): Flow<List<Item>> =
        suggestionsRows(source, hidden).map { rows -> rows.map(Item::displayVersion) }

    @Query("SELECT * FROM sources ORDER BY name, id") fun sources(): Flow<List<Source>>

    @Query("SELECT * FROM sources ORDER BY id") suspend fun snapshot(): List<Source>

    @Query("SELECT * FROM sources WHERE id=:id") suspend fun source(id: String): Source?

    @Upsert suspend fun save(source: Source)

    @Upsert suspend fun save(items: List<Item>)

    @Upsert fun writeBatch(items: List<Item>)

    @Update(entity = Item::class)
    suspend fun assignVersions(rows: List<VersionAssignment>)

    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE id=:id)")
    suspend fun exists(id: String): Boolean

    @Upsert suspend fun save(mark: Mark)

    @Query("SELECT * FROM marks WHERE id=:id") suspend fun mark(id: String): Mark?

    @Query("SELECT * FROM marks WHERE id=:id") fun observeMark(id: String): Flow<Mark?>

    @Query("""SELECT * FROM items WHERE parent=:id AND category NOT IN (:hidden)
        AND parent NOT IN (SELECT id FROM items WHERE category IN (:hidden)) ORDER BY items.rowid""")
    fun episodes(id: String, hidden: List<String> = emptyList()): Flow<List<Item>>

    @Query("""SELECT * FROM items WHERE parent=:id AND category NOT IN (:hidden)
        AND parent NOT IN (SELECT id FROM items WHERE category IN (:hidden))""")
    suspend fun episodeSnapshot(id: String, hidden: List<String> = emptyList()): List<Item>

    @Query("SELECT marks.* FROM marks JOIN items USING(id) WHERE items.parent=:id")
    fun episodeMarks(id: String): Flow<List<Mark>>

    // History aggregation is over marked rows, not the catalog. The parent index resolves episodes;
    // versionKey maps activity on a non-representative series to its visible production card.
    @Query("""WITH history AS (
        SELECT CASE WHEN ep.kind='EPISODE' THEN ep.parent ELSE ep.id END AS cardId, MAX(m.updated) AS activity
        FROM marks m CROSS JOIN items ep ON ep.id=m.id WHERE ep.category NOT IN (:hidden) AND m.position>0 AND
            (ep.kind='EPISODE' OR ep.kind='MOVIE' AND (m.duration<=0 OR m.position<m.duration*0.95))
        GROUP BY cardId
        ), activity AS (
        SELECT CASE WHEN card.versionKey='' THEN card.id ELSE card.versionKey END AS cardKey, MAX(history.activity) AS activity
        FROM history JOIN items card ON card.id=history.cardId GROUP BY cardKey
        ), cards AS (
        SELECT i.*,activity.activity FROM activity JOIN items i ON i.versionKey=activity.cardKey WHERE i.versionKey!=''
        UNION ALL
        SELECT i.*,activity.activity FROM activity JOIN items i ON i.id=activity.cardKey WHERE i.versionKey=''
        ), candidates AS (
        SELECT i.* FROM cards i WHERE i.category NOT IN (:hidden) AND (:source='' OR i.source=:source) AND i.kind IN ('MOVIE','SERIES')
        AND (i.kind!='SERIES' OR EXISTS(SELECT 1 FROM items ep LEFT JOIN marks m ON m.id=ep.id
            WHERE ep.category NOT IN (:hidden) AND ep.parent=i.id AND (m.id IS NULL OR m.duration<=0 OR m.position<m.duration*0.90)))
        ) SELECT i.* FROM candidates i WHERE i.isRepresentative=1 OR (i.versionKey!='' AND NOT EXISTS(
            SELECT 1 FROM candidates v WHERE v.versionKey=i.versionKey AND v.versionRank<i.versionRank))
        ORDER BY i.activity DESC,i.id LIMIT 24""")
    fun continueWatchingRows(source: String, hidden: List<String> = emptyList()): Flow<List<Item>>

    fun continueWatching(source: String, hidden: List<String> = emptyList()): Flow<List<Item>> =
        continueWatchingRows(source, hidden).map { rows -> rows.map(Item::displayVersion) }

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
        hidden: List<String> = emptyList(),
    ): Flow<List<Item>> = catalogRows(browseQuery(
        CatalogFilter(source, kind, category, query, shelf, year, genre, language, minRating, hidden), queryLimit))

    fun browse(source: String, kind: String, category: String, query: String, shelf: String, limit: Int,
               year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0, hidden: List<String> = emptyList()): Flow<List<Item>> =
        browseRows(source, kind, category, query, shelf, if (limit == Int.MAX_VALUE) -1 else limit,
            year, genre, language, minRating, hidden).map { rows -> rows.map(Item::displayVersion) }

    @Query("SELECT * FROM items WHERE id=:id")
    suspend fun item(id: String): Item?

    @Query("SELECT * FROM items WHERE source=:source AND kind=:kind AND category NOT IN (:hidden)")
    suspend fun versionCandidates(source: String, kind: String, hidden: List<String> = emptyList()): List<Item>

    @Query("SELECT * FROM items WHERE versionKey=:key ORDER BY versionRank")
    suspend fun versionRows(key: String): List<Item>

    fun categories(kind: String, source: String, hidden: List<String> = emptyList()): Flow<List<String>> =
        catalogCategories(categoryCountsQuery(CatalogFilter(source = source, kind = kind, hidden = hidden), categoriesOnly = true))
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
    version = 8,
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
    companion object {
        // Do not run Kotlin normalization while opening a large v7 database. Defaults keep every
        // legacy row visible (including marks); the next successful import atomically groups it.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN versionKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN isRepresentative INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE items ADD COLUMN displayTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN displayYear TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN versionRank INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP INDEX IF EXISTS index_items_source")
                db.execSQL("CREATE INDEX index_items_source_kind_category_title ON items(source,kind,category,title)")
                db.execSQL("CREATE INDEX index_items_kind_isRepresentative_title ON items(kind,isRepresentative,title)")
                db.execSQL("CREATE INDEX index_items_parent ON items(parent)")
                db.execSQL("CREATE INDEX index_items_versionKey ON items(versionKey)")
            }
        }
    }

    abstract fun library(): LibraryDao

    abstract fun guide(): GuideDao
}
