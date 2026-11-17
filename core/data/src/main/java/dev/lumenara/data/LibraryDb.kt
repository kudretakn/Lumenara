package dev.lumenara.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    // kind/category/title covers category counts and selected categories across all sources.
    // kind/representative/title serves the unfiltered movie/series grid. Keep kind/title for
    // filtered fallback: the representative OR cannot constrain that index's middle column.
    // parent serves episodes, progress and orphan checks; versionKey serves bounded sibling probes.
    // No tvgId index: guide resolution is in Kotlin and multi-source LIVE dedup groups by more than tvgId.
    indices = [Index("source", "kind", "category", "title"),
        Index("kind", "category", "title"),
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
    @ColumnInfo(defaultValue = "''") val providerId: String = "",
    @ColumnInfo(defaultValue = "''") val tvgName: String = "",
    @ColumnInfo(defaultValue = "''") val tvgGroup: String = "",
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
data class KindCategoryCount(val kind: String, val category: String, val count: Int)

/** Lightweight projection; this is not a persisted entity or schema change. */
data class LiveChannelId(val id: String, val source: String, val title: String = "")

/** Columns needed to group versions; deliberately excludes summaries, cast and playback URLs. */
data class VersionCandidate(
    val id: String,
    val source: String,
    val title: String,
    val kind: String,
    val search: String,
    val year: String,
    val category: String,
) {
    fun asItem() = Item(id = id, source = source, title = title, category = category,
        kind = kind, tvgId = "", encryptedUrl = "", search = search, generation = 0,
        logo = "", year = year)
}

@Dao
interface LibraryDao {
    @Query("""SELECT id,source,title,category,kind,tvgId,year,parent,providerId,tvgName,tvgGroup,versionKey,
        CASE WHEN :legacy=1 AND providerId='' THEN encryptedUrl ELSE '' END AS legacyUrl FROM items WHERE source=:source""")
    suspend fun carryRows(source: String, legacy: Boolean = false): List<CarryRow>

    @Query("SELECT * FROM item_redirects")
    suspend fun redirects(): List<ItemRedirect>

    @Query("SELECT newId FROM item_redirects WHERE oldId=:id")
    suspend fun redirectedId(id: String): String?

    @Upsert suspend fun redirect(row: ItemRedirect)
    @Upsert suspend fun saveRedirects(rows: List<ItemRedirect>)
    @Query("DELETE FROM item_redirects WHERE oldId=newId") suspend fun deleteSelfRedirects()

    @Query("UPDATE item_redirects SET newId=:newId WHERE newId=:oldId")
    suspend fun redirectTarget(oldId: String, newId: String)

    @Query("SELECT * FROM collections WHERE id=:id")
    suspend fun member(id: String): Member?

    @Query("SELECT * FROM collections") suspend fun carryMembers(): List<Member>

    @Query("DELETE FROM marks WHERE id=:id") suspend fun deleteMark(id: String)
    @Query("DELETE FROM collections WHERE id=:id") suspend fun deleteMember(id: String)
    @Query("DELETE FROM mappings WHERE item=:id") suspend fun deleteMapping(id: String)

    @Query("""SELECT i.id FROM items i WHERE i.source=:source AND (
        EXISTS(SELECT 1 FROM marks m WHERE m.id=i.id AND (m.favorite=1 OR m.listed=1 OR m.position>0 OR m.updated>0))
        OR EXISTS(SELECT 1 FROM collections c WHERE c.id=i.id)
        OR EXISTS(SELECT 1 FROM mappings m WHERE m.item=i.id))""")
    suspend fun carryProtectedIds(source: String): List<String>

    @Query("UPDATE items SET generation=:generation WHERE id IN (:ids)")
    suspend fun retainCarryRows(ids: List<String>, generation: Long)

    @Query("""UPDATE items SET generation=:generation,versionKey=id,isRepresentative=1,
        displayTitle=title,displayYear=year,versionRank=0 WHERE id IN (:ids)""")
    suspend fun isolateCarryRows(ids: List<String>, generation: Long)
    @RawQuery(observedEntities = [Item::class])
    fun catalogRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [Item::class, Mark::class])
    fun markedCatalogRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [Item::class, Member::class])
    fun memberCatalogRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [Item::class, Mark::class])
    fun catalogCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [Item::class, Mark::class])
    fun catalogKinds(query: SupportSQLiteQuery): Flow<List<String>>

    @RawQuery(observedEntities = [Item::class])
    fun catalogCategoryCounts(query: SupportSQLiteQuery): Flow<List<LiveCategoryCount>>

    @RawQuery(observedEntities = [Item::class, Feed::class, Slot::class, GuideName::class, Mapping::class])
    fun liveNowRows(query: SupportSQLiteQuery): Flow<List<LiveNow>>

    fun liveNow(rows: List<Item>, now: Long): Flow<List<LiveNow>> = liveNowRows(liveNowQuery(rows, now))

    @RawQuery(observedEntities = [Item::class])
    fun catalogKindCategoryCounts(query: SupportSQLiteQuery): Flow<List<KindCategoryCount>>

    @RawQuery(observedEntities = [Item::class])
    fun catalogCategories(query: SupportSQLiteQuery): Flow<List<String>>

    // Visibility only needs names, never representative selection or counts over the catalog.
    @Query("SELECT DISTINCT category FROM items WHERE kind IN ('LIVE','MOVIE','SERIES') ORDER BY category")
    fun categoryNames(): Flow<List<String>>
    suspend fun liveAtOffset(source: String, offset: Int, hidden: List<String> = emptyList()): Item? =
        livePage(source, "", "", "", 1, offset, hidden).first().firstOrNull()

    @RawQuery(observedEntities = [Item::class])
    fun liveRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [Item::class])
    fun liveTotal(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [Item::class, Mark::class])
    fun liveFavoriteRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [Item::class, Mark::class])
    fun liveFavoriteTotal(query: SupportSQLiteQuery): Flow<Int>

    fun liveFilteredRows(query: SupportSQLiteQuery, shelf: String): Flow<List<Item>> =
        if (shelf == "favorite") liveFavoriteRows(query) else liveRows(query)

    @RawQuery
    suspend fun livePosition(query: SupportSQLiteQuery): Int?

    @RawQuery(observedEntities = [Item::class])
    fun liveOrder(query: SupportSQLiteQuery): Flow<List<LiveChannelId>>

    fun livePage(source: String, category: String, query: String, shelf: String, limit: Int,
        offset: Int, hidden: List<String> = emptyList()): Flow<List<Item>> =
        liveFilteredRows(livePageQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden), limit, offset = offset), shelf)

    fun livePageAfter(source: String, category: String, query: String, shelf: String, limit: Int,
        after: LiveCursor?, backwards: Boolean = false, hidden: List<String> = emptyList()): Flow<List<Item>> =
        liveFilteredRows(livePageQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden), limit, after, backwards), shelf)

    fun liveNeighbors(source: String, category: String, query: String, shelf: String, limit: Int,
        id: String, backwards: Boolean = false, hidden: List<String> = emptyList()): Flow<List<Item>> =
        liveFilteredRows(livePageQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden), limit, backwards = backwards, targetId = id), shelf)

    fun liveCount(source: String, category: String, query: String, shelf: String,
        hidden: List<String> = emptyList()): Flow<Int> {
        val sql = liveCountQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden))
        return if (shelf == "favorite") liveFavoriteTotal(sql) else liveTotal(sql)
    }

    suspend fun liveIndex(source: String, id: String, category: String = "", query: String = "",
        shelf: String = "", hidden: List<String> = emptyList()): Int? =
        livePosition(liveIndexQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden), id))

    fun liveChannelOrder(source: String, hidden: List<String> = emptyList()): Flow<List<LiveChannelId>> =
        liveOrder(liveChannelOrderQuery(CatalogFilter(source = source, hidden = hidden))).map { rows ->
            // The SQL streams title/id. Preserve the public source/title/id order in O(n),
            // sorting only the (usually single) source keys, when a full order is requested.
            if (source.isNotEmpty()) rows else rows.groupBy { it.source }.toSortedMap().values.flatten()
        }

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

    fun liveCategoryCounts(source: String, hidden: List<String> = emptyList()): Flow<List<LiveCategoryCount>> =
        catalogCategoryCounts(liveCategoryCountsQuery(source, hidden))

    fun categoryCounts(kind: String, source: String, hidden: List<String> = emptyList()): Flow<List<LiveCategoryCount>> =
        catalogCategoryCounts(categoryCountsQuery(CatalogFilter(source = source, kind = kind, hidden = hidden)))

    fun sourceCategoryCounts(source: String, hidden: List<String> = emptyList()): Flow<List<KindCategoryCount>> =
        catalogKindCategoryCounts(sourceCategoryCountsQuery(source, hidden))

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
        memberCatalogRows(shelfRowsQuery(CatalogFilter(source = source, hidden = hidden), 60, "i.year,i.title", includeEpisodes = true) {
            CatalogPredicate("EXISTS(SELECT 1 FROM collections WHERE id=$it.id AND name=?)", listOf(name))
        })

    fun members(source: String, name: String, hidden: List<String> = emptyList()): Flow<List<Item>> =
        membersRows(source, name, hidden).map { rows -> rows.map(Item::displayVersion) }

    fun suggestionsRows(source: String, hidden: List<String> = emptyList()): Flow<List<Item>> =
        markedCatalogRows(shelfRowsQuery(CatalogFilter(source = source, hidden = hidden), 24, "CAST(i.rating AS REAL) DESC,i.title") {
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

    @androidx.room.Update(entity = Item::class)
    suspend fun assignVersions(rows: List<VersionAssignment>)

    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE id=:id)")
    suspend fun exists(id: String): Boolean

    @Upsert suspend fun save(mark: Mark)

    // Observe only playback history; catalog changes and list removals also invalidate this query.
    @Query("""SELECT marks.* FROM marks JOIN items USING(id)
        WHERE marks.position>0 AND (items.kind='EPISODE' OR
            items.kind='MOVIE' AND (marks.duration<=0 OR marks.position<marks.duration*0.95))
        ORDER BY marks.updated DESC,marks.id""")
    fun watchNextMarks(): Flow<List<Mark>>

    @Query("SELECT * FROM marks WHERE id=:id") suspend fun mark(id: String): Mark?

    @Query("SELECT * FROM marks WHERE id IN (:ids)")
    suspend fun marksByIds(ids: List<String>): List<Mark>

    // One indexed primary-key probe per visible card, with one Room observer for the grid.
    @Query("SELECT * FROM marks WHERE id IN (:ids)")
    fun visibleMarks(ids: List<String>): Flow<List<Mark>>

    @Query("SELECT * FROM marks WHERE favorite=1 OR listed=1 OR position>0 OR updated>0")
    suspend fun backupMarks(): List<Mark>

    @Query("SELECT * FROM marks WHERE id=:id") fun observeMark(id: String): Flow<Mark?>

    @Query("""SELECT * FROM items WHERE parent=:id AND category NOT IN (:hidden)
        AND NOT EXISTS(SELECT 1 FROM items s WHERE s.id=:id AND s.category IN (:hidden)) ORDER BY items.rowid""")
    fun episodes(id: String, hidden: List<String> = emptyList()): Flow<List<Item>>

    @Query("""SELECT * FROM items WHERE parent=:id AND category NOT IN (:hidden)
        AND NOT EXISTS(SELECT 1 FROM items s WHERE s.id=:id AND s.category IN (:hidden))""")
    suspend fun episodeSnapshot(id: String, hidden: List<String> = emptyList()): List<Item>

    @Query("SELECT marks.* FROM marks JOIN items USING(id) WHERE items.parent=:id")
    fun episodeMarks(id: String): Flow<List<Mark>>

    // A bounded category projection supplies history visibility without the catalog names scan.
    // Indexed existence probes retain categoryNames' existing top-level category policy.
    @Query("""WITH watched AS (
        SELECT i.id,i.parent,i.kind,i.category FROM marks m CROSS JOIN items i ON i.id=m.id WHERE m.position>0
        ), parents AS (SELECT DISTINCT parent FROM watched WHERE kind='EPISODE'), names AS (
        SELECT category FROM watched
        UNION SELECT i.category FROM parents p JOIN items i ON i.id=p.parent
        UNION SELECT ep.category FROM parents p CROSS JOIN items ep ON ep.parent=p.parent
        ) SELECT category FROM names n WHERE EXISTS(
            SELECT 1 FROM items i INDEXED BY index_items_kind_category_title
            WHERE i.kind IN ('LIVE','MOVIE','SERIES') AND i.category=n.category)""")
    fun continueWatchingCategories(): Flow<List<String>>

    // Aggregate only marked rows, then choose a representative among eligible history cards.
    // Do not expand history to every version: an unplayed/completed sibling must not replace
    // the version the user can actually resume. parent resolves the remaining-episode probe.
    @Query("""WITH history AS (
        SELECT CASE WHEN ep.kind='EPISODE' THEN ep.parent ELSE ep.id END AS cardId, MAX(m.updated) AS activity
        FROM marks m CROSS JOIN items ep ON ep.id=m.id WHERE ep.category NOT IN (:hidden) AND m.position>0 AND
            (ep.kind='EPISODE' OR ep.kind='MOVIE' AND (m.duration<=0 OR m.position<m.duration*0.95))
        GROUP BY cardId
        ), candidates AS (
        SELECT i.*,history.activity FROM history JOIN items i ON i.id=history.cardId
        WHERE i.category NOT IN (:hidden) AND (:source='' OR i.source=:source) AND i.kind IN ('MOVIE','SERIES')
        AND (i.kind!='SERIES' OR EXISTS(SELECT 1 FROM items ep LEFT JOIN marks m ON m.id=ep.id
            WHERE ep.category NOT IN (:hidden) AND ep.parent=i.id AND (m.id IS NULL OR m.duration<=0 OR m.position<m.duration*0.90)))
        ), activity AS (
        SELECT CASE WHEN versionKey='' THEN id ELSE versionKey END AS cardKey, MAX(activity) AS lastActivity
        FROM candidates GROUP BY cardKey
        ) SELECT items.* FROM candidates i JOIN items ON items.id=i.id JOIN activity a
        ON a.cardKey=CASE WHEN i.versionKey='' THEN i.id ELSE i.versionKey END
        WHERE i.isRepresentative=1 OR (i.versionKey!='' AND NOT EXISTS(
            SELECT 1 FROM candidates v WHERE v.versionKey=i.versionKey AND v.versionRank<i.versionRank))
        ORDER BY a.lastActivity DESC,i.id LIMIT 24""")
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
    ): Flow<List<Item>> {
        val sql = browseQuery(CatalogFilter(source, kind, category, query, shelf, year, genre,
            language, minRating, hidden), queryLimit)
        return if (shelf in listOf("favorite", "list", "resume")) markedCatalogRows(sql)
            else catalogRows(sql)
    }

    fun browse(source: String, kind: String, category: String, query: String, shelf: String, limit: Int,
               year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0, hidden: List<String> = emptyList()): Flow<List<Item>> =
        browseRows(source, kind, category, query, shelf, if (limit == Int.MAX_VALUE) -1 else limit,
            year, genre, language, minRating, hidden).map { rows -> rows.map(Item::displayVersion) }

    fun browsePage(source: String, kind: String, category: String, query: String, shelf: String,
        limit: Int, offset: Int, year: String = "", genre: String = "", language: String = "",
        minRating: Double = 0.0, hidden: List<String> = emptyList()): Flow<List<Item>> {
        val sql = browseQuery(CatalogFilter(source, kind, category, query, shelf, year, genre,
            language, minRating, hidden), limit, offset)
        val rows = if (shelf in listOf("favorite", "list", "resume")) markedCatalogRows(sql) else catalogRows(sql)
        return rows.map { page -> page.map(Item::displayVersion) }
    }

    fun browseTotal(source: String, kind: String, category: String, query: String, shelf: String,
        year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0,
        hidden: List<String> = emptyList()): Flow<Int> = catalogCount(browseCountQuery(
            CatalogFilter(source, kind, category, query, shelf, year, genre, language, minRating, hidden)))

    fun browseKinds(source: String, kind: String, category: String, query: String, shelf: String,
        year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0,
        hidden: List<String> = emptyList()): Flow<List<String>> = catalogKinds(browseKindsQuery(
            CatalogFilter(source, kind, category, query, shelf, year, genre, language, minRating, hidden)))

    suspend fun browseIndex(source: String, kind: String, category: String, query: String, shelf: String,
        id: String, year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0,
        hidden: List<String> = emptyList()): Int? = livePosition(browseIndexQuery(
            CatalogFilter(source, kind, category, query, shelf, year, genre, language, minRating, hidden), id))

    @Query("SELECT * FROM items WHERE id=:id")
    suspend fun item(id: String): Item?

    @Query("""SELECT id, source, title, kind, search, year, category FROM items WHERE source=:source
        AND kind=:kind AND category NOT IN (:hidden) AND (:generation<0 OR generation=:generation)""")
    suspend fun versionCandidates(source: String, kind: String, hidden: List<String> = emptyList(), generation: Long = -1): List<VersionCandidate>

    @Query("SELECT * FROM items WHERE versionKey=:key ORDER BY versionRank")
    suspend fun versionRows(key: String): List<Item>

    fun categories(kind: String, source: String, hidden: List<String> = emptyList()): Flow<List<String>> =
        catalogCategories(categoryCountsQuery(CatalogFilter(source = source, kind = kind, hidden = hidden), categoriesOnly = true))
}

// A single encrypted undo record; deletion participates in the restore's Room commit.
@Entity(tableName = "restore_pending")
internal data class RestorePending(@PrimaryKey val id: Int = 1, val snapshot: String)

@Dao
internal interface RestoreDao {
    @Query("SELECT snapshot FROM restore_pending WHERE id=1") suspend fun pending(): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(row: RestorePending)
    @Query("DELETE FROM restore_pending") suspend fun clear()
}

// Durable aliases also let the TV provider reconcile its external rows after a process restart.
@Entity(tableName = "item_redirects", indices = [Index("newId")], foreignKeys = [ForeignKey(
    entity = Item::class, parentColumns = ["id"], childColumns = ["newId"], onDelete = ForeignKey.CASCADE)])
data class ItemRedirect(@PrimaryKey val oldId: String, val newId: String)

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
            RestorePending::class,
            ItemRedirect::class,
        ],
    version = 12,
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
    internal abstract fun restoreJournal(): RestoreDao

    companion object {
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN providerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN tvgName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN tvgGroup TEXT NOT NULL DEFAULT ''")
                db.execSQL("""CREATE TABLE IF NOT EXISTS item_redirects (oldId TEXT NOT NULL,
                    newId TEXT NOT NULL, PRIMARY KEY(oldId), FOREIGN KEY(newId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX index_item_redirects_newId ON item_redirects(newId)")
            }
        }
        // Data-only migration: collapse cards immediately, including unrefreshed v7 rows.
        // Keep every stream, mark, collection and episode ID for the detail version picker.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val sources = db.query("SELECT id FROM sources").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                db.compileStatement("""UPDATE items SET versionKey=?,isRepresentative=?,
                    displayTitle=?,displayYear=?,versionRank=? WHERE id=?""").use { update ->
                    for (source in sources) for (kind in listOf("MOVIE", "SERIES")) {
                        val rows = db.query("""SELECT id,source,title,kind,search,year,category
                            FROM items WHERE source=? AND kind=?""", arrayOf(source, kind)).use { cursor ->
                            buildList {
                                while (cursor.moveToNext()) add(VersionCandidate(cursor.getString(0),
                                    cursor.getString(1), cursor.getString(2), cursor.getString(3),
                                    cursor.getString(4), cursor.getString(5), cursor.getString(6)).asItem())
                            }
                        }
                        for (row in rows.versionAssignments()) {
                            update.bindString(1, row.versionKey)
                            update.bindLong(2, if (row.isRepresentative) 1L else 0L)
                            update.bindString(3, row.displayTitle)
                            update.bindString(4, row.displayYear)
                            update.bindLong(5, row.versionRank.toLong())
                            update.bindString(6, row.id)
                            update.executeUpdateDelete()
                        }
                    }
                }
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS restore_pending (id INTEGER NOT NULL, snapshot TEXT NOT NULL, PRIMARY KEY(id))")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX index_items_kind_category_title ON items(kind,category,title)")
                db.execSQL("CREATE INDEX index_programmes_start ON programmes(start)")
            }
        }

        // v8 kept legacy rows visible with defaults; v11 now backfills their grouping keys.
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
