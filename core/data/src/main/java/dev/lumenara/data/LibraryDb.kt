package dev.lumenara.data

import androidx.room.*
import dev.lumenara.catalog.displayTitle
import dev.lumenara.catalog.searchable
import dev.lumenara.catalog.isAdultCategory
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import dev.lumenara.catalog.categorySortKey

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
    // Schema 13 streams stored sortKey/id order for source/kind/category pages.
    // source/id bounds refresh/backfill batches; channel indexes serve direct number entry.
    // Keep legacy title indexes for bounded history probes and older migration paths.
    indices = [Index("source", "kind", "category", "title"),
        Index("kind", "category", "title"),
        Index("kind", "isRepresentative", "title"), Index("kind", "title"),
        Index("parent"), Index("versionKey"), Index("source", "kind", "sortKey", "id"),
        Index("kind", "sortKey", "id"), Index("source", "channelNumber"), Index("source", "safeChannelNumber"), Index("source", "id"),
        Index("source", "kind", "category", "sortKey", "id"), Index("kind", "category", "sortKey", "id")],
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
    @ColumnInfo(defaultValue = "''") val cleanTitle: String = title.displayTitle(),
    @ColumnInfo(defaultValue = "''") val sortKey: String = searchable(cleanTitle),
    @ColumnInfo(defaultValue = "0") val isAdult: Boolean = isAdultCategory(category),
    @ColumnInfo(defaultValue = "0") val channelNumber: Int = 0,
    @ColumnInfo(defaultValue = "0") val safeChannelNumber: Int = 0,
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

data class LiveCategoryCount(val category: String, val count: Int, val searchKey: String = "")
data class KindCategoryCount(val kind: String, val category: String, val count: Int, val searchKey: String = "")

/** Lightweight projection; this is not a persisted entity or schema change. */
data class VersionMarkGroup(val id: String, val versionKey: String)

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
    @Query("""SELECT NOT EXISTS(SELECT 1 FROM sources s
        LEFT JOIN catalog_derived_state d ON d.source=s.id
        WHERE (:source='' OR s.id=:source) AND (d.source IS NULL OR d.phase!='done'))""")
    fun catalogReady(source: String): Flow<Boolean>

    @Query("SELECT DISTINCT category FROM items WHERE kind IN ('LIVE','MOVIE','SERIES') ORDER BY category")
    fun originalCategoryNames(): Flow<List<String>>

    fun visibleAdultCategoryNames(): Flow<List<String>> = withCatalog("") { ready ->
        if (ready) adultCategoryNames() else originalCategoryNames().map { it.filter(::isAdultCategory) }
    }.distinctUntilChanged()

    @RawQuery(observedEntities = [Item::class, VersionBackfillState::class])
    fun originalCategoryCounts(query: SupportSQLiteQuery): Flow<List<KindCategoryCount>>

    @RawQuery(observedEntities = [Item::class, VersionBackfillState::class])
    fun originalHubTotals(query: SupportSQLiteQuery): Flow<List<HubTotal>>

    fun fallbackHubTotals(source: String, hidden: List<String>): Flow<List<HubTotal>> =
        originalHubTotals(fallbackHubTotalsQuery(source, hidden))

    @Query("""SELECT id,source,title,category,kind,tvgId,year,parent,providerId,tvgName,tvgGroup,
        CASE WHEN EXISTS(SELECT 1 FROM version_backfill_state b WHERE b.id=1 AND
            (b.phase IN ('normalize','resolve') OR (b.phase='apply' AND items.id>b.cursor)))
            THEN id ELSE versionKey END AS versionKey, '' AS urlHash,
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
    @RawQuery(observedEntities = [Item::class, VersionBackfillState::class])
    fun catalogRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [Item::class, Mark::class, VersionBackfillState::class])
    fun markedCatalogRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [Item::class, Member::class, VersionBackfillState::class])
    fun memberCatalogRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [Item::class, Mark::class, VersionBackfillState::class])
    fun catalogCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [Item::class, Mark::class, VersionBackfillState::class])
    fun catalogKinds(query: SupportSQLiteQuery): Flow<List<String>>

    @RawQuery(observedEntities = [CategoryCount::class])
    fun catalogCategoryCounts(query: SupportSQLiteQuery): Flow<List<LiveCategoryCount>>

    @RawQuery(observedEntities = [Item::class, Feed::class, Slot::class, GuideName::class, Mapping::class])
    fun liveNowRows(query: SupportSQLiteQuery): Flow<List<LiveNow>>

    fun liveNow(rows: List<Item>, now: Long): Flow<List<LiveNow>> = liveNowRows(liveNowQuery(rows, now))

    @RawQuery(observedEntities = [CategoryCount::class])
    fun catalogKindCategoryCounts(query: SupportSQLiteQuery): Flow<List<KindCategoryCount>>

    @RawQuery(observedEntities = [CategoryCount::class])
    fun catalogCategories(query: SupportSQLiteQuery): Flow<List<String>>

    // Visibility only needs names, never representative selection or counts over the catalog.
    @Query("SELECT DISTINCT category FROM category_counts ORDER BY category")
    fun categoryNames(): Flow<List<String>>
    @Query("SELECT DISTINCT category FROM category_counts WHERE isAdult=1 ORDER BY category")
    fun adultCategoryNames(): Flow<List<String>>
    suspend fun liveAtOffset(source: String, offset: Int, hidden: List<String> = emptyList()): Item? =
        livePage(source, "", "", "", 1, offset, hidden).first().firstOrNull()

    @RawQuery(observedEntities = [Item::class])
    fun liveRows(query: SupportSQLiteQuery): Flow<List<Item>>

    @RawQuery(observedEntities = [CategoryCount::class, CatalogStat::class])
    fun derivedTotal(query: SupportSQLiteQuery): Flow<Int>

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
        offset: Int, hidden: List<String> = emptyList()): Flow<List<Item>> = withCatalog(source) { ready ->
        liveFilteredRows(livePageQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden, derived = ready), limit, offset = offset), shelf)
    }

    fun livePageAfter(source: String, category: String, query: String, shelf: String, limit: Int,
        after: LiveCursor?, backwards: Boolean = false, hidden: List<String> = emptyList()): Flow<List<Item>> = withCatalog(source) { ready ->
        liveFilteredRows(livePageQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden, derived = ready), limit, after, backwards), shelf)
    }

    fun liveNeighbors(source: String, category: String, query: String, shelf: String, limit: Int,
        id: String, backwards: Boolean = false, hidden: List<String> = emptyList()): Flow<List<Item>> = withCatalog(source) { ready ->
        liveFilteredRows(livePageQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden, derived = ready), limit, backwards = backwards, targetId = id), shelf)
    }

    fun liveCount(source: String, category: String, query: String, shelf: String,
        hidden: List<String> = emptyList()): Flow<Int> = withCatalog(source) { ready ->
        val sql = liveCountQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden, derived = ready))
        when {
            shelf == "favorite" -> liveFavoriteTotal(sql)
            ready && query.isEmpty() -> derivedTotal(sql)
            else -> liveTotal(sql)
        }
    }

    suspend fun liveIndex(source: String, id: String, category: String = "", query: String = "",
        shelf: String = "", hidden: List<String> = emptyList()): Int? =
        livePosition(liveIndexQuery(CatalogFilter(source = source, category = category, query = query,
            shelf = shelf, hidden = hidden, derived = catalogReady(source).first()), id))

    fun liveChannelOrder(source: String, hidden: List<String> = emptyList()): Flow<List<LiveChannelId>> = withCatalog(source) { ready ->
        liveOrder(liveChannelOrderQuery(CatalogFilter(source = source, hidden = hidden, derived = ready))).map { rows ->
            // The SQL streams title/id. Preserve the public source/title/id order in O(n),
            // sorting only the (usually single) source keys, when a full order is requested.
            if (source.isNotEmpty()) rows else rows.groupBy { it.source }.toSortedMap().values.flatten()
        }
    }

    @Query("""SELECT * FROM items INDEXED BY sqlite_autoindex_items_1 WHERE id IN (:ids) AND kind='LIVE'
        AND category NOT IN (:hidden) AND (:source='' OR source=:source)""")
    fun liveByIds(source: String, ids: List<String>, hidden: List<String> = emptyList()): Flow<List<Item>>

    @Query("""SELECT i.id,
        (SELECT COUNT(*) FROM items p WHERE p.source=i.source AND p.kind='LIVE' AND p.rowid<=i.rowid) AS channelNumber,
        CASE WHEN i.category IN (:hidden) THEN 0 ELSE
        (SELECT COUNT(*) FROM items p WHERE p.source=i.source AND p.kind='LIVE' AND p.rowid<=i.rowid
            AND p.category NOT IN (:hidden)) END AS safeChannelNumber
        FROM items i WHERE i.id IN (:ids) AND i.kind='LIVE' AND (:source='' OR i.source=:source)""")
    suspend fun providerChannelNumbers(source: String, ids: List<String>, hidden: List<String>): List<StoredChannelNumber>

    @Query("""SELECT * FROM items NOT INDEXED WHERE source=:source AND kind='LIVE'
        AND category NOT IN (:hidden) ORDER BY rowid LIMIT 1 OFFSET :offset""")
    suspend fun providerChannel(source: String, offset: Int, hidden: List<String>): Item?

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
        categoryCounts("LIVE", source, hidden)

    fun categoryCounts(kind: String, source: String, hidden: List<String> = emptyList()): Flow<List<LiveCategoryCount>> = withCatalog(source) { ready ->
        val filter = CatalogFilter(source = source, kind = kind, hidden = hidden)
        if (ready) catalogCategoryCounts(categoryCountsQuery(filter))
        else fallbackCategoryCounts(filter).map { rows ->
            rows.groupBy { it.category }.map { (name, groups) -> LiveCategoryCount(name, groups.sumOf { it.count }, searchable(name)) }
                .sortedWith(compareBy({ categorySortKey(it.category) }, { it.category }))
        }
    }

    fun sourceCategoryCounts(source: String, hidden: List<String> = emptyList()): Flow<List<KindCategoryCount>> = withCatalog(source) { ready ->
        if (ready) catalogKindCategoryCounts(sourceCategoryCountsQuery(source, hidden))
        else fallbackCategoryCounts(CatalogFilter(source = source, hidden = hidden))
    }

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

    @androidx.room.Update(entity = Item::class)
    suspend fun assignTmdb(rows: List<TmdbAssignment>)

    @Query("SELECT * FROM items WHERE id IN (:ids) AND kind IN ('MOVIE','SERIES')")
    suspend fun versionSeeds(ids: List<String>): List<Item>

    // CatalogStage.read has a synchronous callback, already inside the IO import transaction.
    @Query("SELECT * FROM items WHERE id IN (:ids) AND kind IN ('MOVIE','SERIES')")
    fun importedVersionSeeds(ids: List<String>): List<Item>

    @RawQuery
    suspend fun relatedVersions(query: SupportSQLiteQuery): List<Item>

    @Query("""SELECT id,versionKey FROM items INDEXED BY index_items_source_id
        WHERE source=:source AND id>:cursor AND kind IN ('MOVIE','SERIES') AND isRepresentative=1
        ORDER BY id LIMIT :limit""")
    suspend fun versionMarkGroups(source: String, cursor: String, limit: Int): List<VersionMarkGroup>

    @Query("SELECT m.* FROM items i JOIN marks m ON m.id=i.id WHERE i.versionKey=:key")
    suspend fun versionGroupMarks(key: String): List<Mark>

    @Query("SELECT id FROM items WHERE versionKey=:key")
    suspend fun versionGroupIds(key: String): List<String>

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
        UNION SELECT i.category FROM parents p CROSS JOIN items i ON i.id=p.parent
        UNION SELECT ep.category FROM parents p CROSS JOIN items ep INDEXED BY index_items_parent ON ep.parent=p.parent
        ) SELECT category FROM names n WHERE EXISTS(
            SELECT 1 FROM items i INDEXED BY index_items_kind_category_title
            WHERE i.kind IN ('LIVE','MOVIE','SERIES') AND i.category=n.category)""")
    fun continueWatchingCategories(): Flow<List<String>>

    // HubHistoryQuery's bounded ID selection, with legacy cards isolated until their
    // resumable backfill batch commits. Observing the state also refreshes phase changes.
    @Query("""WITH history AS (
        SELECT CASE WHEN ep.kind='EPISODE' THEN ep.parent ELSE ep.id END AS cardId, MAX(m.updated) AS activity
        FROM marks m CROSS JOIN items ep ON ep.id=m.id WHERE ep.category NOT IN (:hidden) AND m.position>0 AND
            (ep.kind='EPISODE' OR ep.kind='MOVIE' AND (m.duration<=0 OR m.position<m.duration*0.95))
        GROUP BY cardId
        ), candidates AS (
        SELECT i.id,CASE WHEN COALESCE(i.versionKey,'')='' OR EXISTS(
            SELECT 1 FROM version_backfill_state b WHERE b.id=1
            AND (b.phase IN ('normalize','resolve') OR (b.phase='apply' AND i.id>b.cursor)))
            THEN i.id ELSE i.versionKey END AS cardKey,
            i.versionRank,i.isRepresentative,i.versionKey!='' AS hasVersion,MAX(h.activity) AS activity
        FROM history h CROSS JOIN items i ON i.id=h.cardId
        WHERE i.category NOT IN (:hidden) AND (:source='' OR i.source=:source) AND i.kind IN ('MOVIE','SERIES')
        AND (i.kind!='SERIES' OR EXISTS(SELECT 1 FROM items ep INDEXED BY index_items_parent
            LEFT JOIN marks m ON m.id=ep.id WHERE ep.parent=i.id AND ep.category NOT IN (:hidden)
            AND (m.id IS NULL OR m.duration<=0 OR m.position<m.duration*0.90)))
        GROUP BY i.id
        ), activity AS (
        SELECT cardKey,MAX(activity) AS lastActivity,MIN(versionRank) AS bestRank
        FROM candidates GROUP BY cardKey
        ), selected AS (
        SELECT c.id,a.lastActivity FROM activity a CROSS JOIN candidates c ON c.cardKey=a.cardKey
        WHERE c.cardKey=c.id OR c.isRepresentative=1 OR (c.hasVersion AND c.versionRank=a.bestRank)
        ORDER BY a.lastActivity DESC,c.id LIMIT 24
        ) SELECT items.* FROM selected CROSS JOIN items ON items.id=selected.id
        ORDER BY selected.lastActivity DESC,items.id""")
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
    ): Flow<List<Item>> = withCatalog(source) { ready ->
        val sql = browseQuery(CatalogFilter(source, kind, category, query, shelf, year, genre,
            language, minRating, hidden, derived = ready), queryLimit)
        if (shelf in listOf("favorite", "list", "resume")) markedCatalogRows(sql)
            else catalogRows(sql)
    }

    fun browse(source: String, kind: String, category: String, query: String, shelf: String, limit: Int,
               year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0, hidden: List<String> = emptyList()): Flow<List<Item>> =
        browseRows(source, kind, category, query, shelf, if (limit == Int.MAX_VALUE) -1 else limit,
            year, genre, language, minRating, hidden).map { rows -> rows.map(Item::displayVersion) }

    fun browsePage(source: String, kind: String, category: String, query: String, shelf: String,
        limit: Int, offset: Int, year: String = "", genre: String = "", language: String = "",
        minRating: Double = 0.0, hidden: List<String> = emptyList()): Flow<List<Item>> = withCatalog(source) { ready ->
        val sql = browseQuery(CatalogFilter(source, kind, category, query, shelf, year, genre,
            language, minRating, hidden, derived = ready), limit, offset)
        val rows = if (shelf in listOf("favorite", "list", "resume")) markedCatalogRows(sql) else catalogRows(sql)
        rows.map { page -> page.map(Item::displayVersion) }
    }

    fun browseTotal(source: String, kind: String, category: String, query: String, shelf: String,
        year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0,
        hidden: List<String> = emptyList()): Flow<Int> = withCatalog(source) { ready ->
        catalogCount(browseCountQuery(CatalogFilter(source, kind, category, query, shelf, year, genre,
            language, minRating, hidden, derived = ready)))
    }

    fun browseKinds(source: String, kind: String, category: String, query: String, shelf: String,
        year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0,
        hidden: List<String> = emptyList()): Flow<List<String>> = withCatalog(source) { ready ->
        catalogKinds(browseKindsQuery(CatalogFilter(source, kind, category, query, shelf, year, genre,
            language, minRating, hidden, derived = ready)))
    }

    suspend fun browseIndex(source: String, kind: String, category: String, query: String, shelf: String,
        id: String, year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0,
        hidden: List<String> = emptyList()): Int? = livePosition(browseIndexQuery(
            CatalogFilter(source, kind, category, query, shelf, year, genre, language, minRating, hidden,
                derived = catalogReady(source).first()), id))

    @Query("SELECT * FROM items WHERE id=:id")
    suspend fun item(id: String): Item?

    @Query("""SELECT id, source, title, kind, search, year, category FROM items WHERE source=:source
        AND kind=:kind AND category NOT IN (:hidden) AND (:generation<0 OR generation=:generation)""")
    suspend fun versionCandidates(source: String, kind: String, hidden: List<String> = emptyList(), generation: Long = -1): List<VersionCandidate>

    @Query("""SELECT * FROM items WHERE versionKey=:key AND NOT EXISTS(
        SELECT 1 FROM version_backfill_state b WHERE b.id=1
        AND (b.phase IN ('normalize','resolve') OR (b.phase='apply' AND items.id>b.cursor))) ORDER BY versionRank""")
    suspend fun versionRows(key: String): List<Item>

    fun categories(kind: String, source: String, hidden: List<String> = emptyList()): Flow<List<String>> = withCatalog(source) { ready ->
        val filter = CatalogFilter(source = source, kind = kind, hidden = hidden)
        if (ready) catalogCategories(categoryCountsQuery(filter, categoriesOnly = true))
        else fallbackCategoryCounts(filter).map { rows -> rows.map { it.category }.distinct() }
    }
}

/** A sources/state probe switches queries only at readiness transitions, never per batch. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun <T> LibraryDao.withCatalog(source: String, read: (Boolean) -> Flow<T>): Flow<T> =
    catalogReady(source).distinctUntilChanged().flatMapLatest { read(it) }

internal fun LibraryDao.fallbackCategoryCounts(filter: CatalogFilter): Flow<List<KindCategoryCount>> =
    originalCategoryCounts(fallbackCategoryCountsQuery(filter)).map { rows ->
        rows.map { it.copy(searchKey = searchable(it.category)) }
            .sortedWith(compareBy({ it.kind }, { categorySortKey(it.category) }, { it.category }))
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
            VersionBackfillState::class,
            VersionBackfillRow::class,
            CatalogDerivedState::class,
            CategoryCount::class,
            CatalogStat::class,
        ],
    version = 13,
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
    abstract fun versionBackfill(): VersionBackfillDao
    abstract fun derived(): CatalogDerivedDao

    companion object {
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                StartupPerf.measure("migration_12_13") {
                    db.execSQL("ALTER TABLE items ADD COLUMN cleanTitle TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE items ADD COLUMN sortKey TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE items ADD COLUMN isAdult INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE items ADD COLUMN channelNumber INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE items ADD COLUMN safeChannelNumber INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("CREATE INDEX index_items_source_kind_sortKey_id ON items(source,kind,sortKey,id)")
                    db.execSQL("CREATE INDEX index_items_kind_sortKey_id ON items(kind,sortKey,id)")
                    db.execSQL("CREATE INDEX index_version_backfill_rows_source_id ON version_backfill_rows(source,id)")
                    db.execSQL("CREATE INDEX index_items_source_id ON items(source,id)")
                    db.execSQL("CREATE INDEX index_items_source_kind_category_sortKey_id ON items(source,kind,category,sortKey,id)")
                    db.execSQL("CREATE INDEX index_items_kind_category_sortKey_id ON items(kind,category,sortKey,id)")
                    db.execSQL("CREATE INDEX index_items_source_channelNumber ON items(source,channelNumber)")
                    db.execSQL("CREATE INDEX index_items_source_safeChannelNumber ON items(source,safeChannelNumber)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS catalog_derived_state (source TEXT NOT NULL, phase TEXT NOT NULL, cursor TEXT NOT NULL, title TEXT NOT NULL, number INTEGER NOT NULL, safeNumber INTEGER NOT NULL, PRIMARY KEY(source), FOREIGN KEY(source) REFERENCES sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS category_counts (source TEXT NOT NULL, kind TEXT NOT NULL, category TEXT NOT NULL, count INTEGER NOT NULL, isAdult INTEGER NOT NULL, sortOrder TEXT NOT NULL, searchKey TEXT NOT NULL, PRIMARY KEY(source,kind,category), FOREIGN KEY(source) REFERENCES sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS catalog_stats (source TEXT NOT NULL, kind TEXT NOT NULL, showAdult INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(source,kind,showAdult), FOREIGN KEY(source) REFERENCES sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                }
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                StartupPerf.measure("migration_11_12") {
                    db.execSQL("ALTER TABLE items ADD COLUMN providerId TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE items ADD COLUMN tvgName TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE items ADD COLUMN tvgGroup TEXT NOT NULL DEFAULT ''")
                    db.execSQL("""CREATE TABLE IF NOT EXISTS item_redirects (oldId TEXT NOT NULL,
                        newId TEXT NOT NULL, PRIMARY KEY(oldId), FOREIGN KEY(newId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                    db.execSQL("CREATE INDEX index_item_redirects_newId ON item_redirects(newId)")
                    db.execSQL("""CREATE TABLE IF NOT EXISTS version_backfill_state (id INTEGER NOT NULL,
                        phase TEXT NOT NULL, cursor TEXT NOT NULL, elapsed INTEGER NOT NULL, total INTEGER NOT NULL, PRIMARY KEY(id))""")
                    db.execSQL("""CREATE TABLE IF NOT EXISTS version_backfill_rows (id TEXT NOT NULL,
                        source TEXT NOT NULL, bucket TEXT NOT NULL, title TEXT NOT NULL, year TEXT NOT NULL,
                        tmdb TEXT NOT NULL, audio INTEGER NOT NULL, resolvedKey TEXT NOT NULL, PRIMARY KEY(id))""")
                    db.execSQL("CREATE INDEX index_version_backfill_rows_bucket_year ON version_backfill_rows(bucket,year)")
                    db.execSQL("CREATE INDEX index_version_backfill_rows_resolvedKey_audio_id ON version_backfill_rows(resolvedKey,audio,id)")
                    db.execSQL("""INSERT INTO version_backfill_state (id,phase,cursor,elapsed,total)
                        SELECT 1,'normalize','',0,0 WHERE EXISTS(SELECT 1 FROM items WHERE kind IN ('MOVIE','SERIES'))""")
                }
            }
        }
        // v11 changes grouping semantics, not schema. v12 schedules the resumable data work.
        // In particular, never normalize the catalog while Room holds its open transaction.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                StartupPerf.measure("migration_10_11") { /* DDL unchanged. */ }
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                StartupPerf.measure("migration_9_10") {
                    db.execSQL("CREATE TABLE IF NOT EXISTS restore_pending (id INTEGER NOT NULL, snapshot TEXT NOT NULL, PRIMARY KEY(id))")
                }
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                StartupPerf.measure("migration_8_9") {
                    db.execSQL("CREATE INDEX index_items_kind_category_title ON items(kind,category,title)")
                    db.execSQL("CREATE INDEX index_programmes_start ON programmes(start)")
                }
            }
        }

        // v8 keeps legacy rows visible; v12's post-frame job backfills their grouping keys.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                StartupPerf.measure("migration_7_8") {
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
    }

    abstract fun library(): LibraryDao

    abstract fun guide(): GuideDao
}
