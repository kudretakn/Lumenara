package dev.lumenara.data

import androidx.room.*
import dev.lumenara.catalog.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "feeds",
    indices = [Index("source")],
    foreignKeys =
        [
            ForeignKey(
                entity = Source::class,
                parentColumns = ["id"],
                childColumns = ["source"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class Feed(@PrimaryKey val id: String, val source: String, val encryptedAddress: String)

@Entity(
    tableName = "programmes",
    primaryKeys = ["feed", "channel", "start"],
    foreignKeys =
        [
            ForeignKey(
                entity = Feed::class,
                parentColumns = ["id"],
                childColumns = ["feed"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("feed", "channel", "end"), Index("start")],
)
data class Slot(
    val feed: String,
    val channel: String,
    val start: Long,
    val end: Long,
    val title: String,
    val description: String,
    val search: String,
)

@Entity(
    tableName = "epgNames",
    primaryKeys = ["feed", "channel", "name"],
    indices = [Index("feed", "name", "channel")],
    foreignKeys =
        [
            ForeignKey(
                entity = Feed::class,
                parentColumns = ["id"],
                childColumns = ["feed"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class GuideName(val feed: String, val channel: String, val name: String)

@Entity(
    tableName = "mappings",
    indices = [Index("feed")],
    foreignKeys =
        [
            ForeignKey(
                entity = Item::class,
                parentColumns = ["id"],
                childColumns = ["item"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = Feed::class,
                parentColumns = ["id"],
                childColumns = ["feed"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class Mapping(@PrimaryKey val item: String, val feed: String, val channel: String)

const val GUIDE_PAGE_SQL = """SELECT * FROM programmes INDEXED BY index_programmes_feed_channel_end
    WHERE feed=:feed AND channel IN (:channels) AND end>:from AND start<:until"""

@Dao
interface GuideDao {
    @Query("""SELECT id,title,tvgId,search,source,category FROM items
        WHERE id IN (:ids) AND kind='LIVE' AND (:source='' OR source=:source)""")
    fun cardChannels(source: String, ids: List<String>): Flow<List<GuideChannel>>

    @RawQuery(observedEntities = [Item::class, Feed::class, Slot::class, GuideName::class, Mapping::class])
    fun cardTargets(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<List<CardGuideTarget>>

    @Query("SELECT id,title,tvgId,search,source,category FROM items WHERE kind='LIVE' AND category NOT IN (:hidden) AND (:source='' OR source=:source)")
    fun searchChannels(source: String, hidden: List<String> = emptyList()): Flow<List<GuideChannel>>

    @RawQuery(observedEntities = [Slot::class])
    fun searchHits(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<List<ProgramHit>>

    @Query("""SELECT p.* FROM programmes p JOIN feeds f ON p.feed=f.id
        WHERE (:source='' OR f.source=:source) AND p.start<=:now AND p.end>:now""")
    fun airingSlots(source: String, now: Long): Flow<List<Slot>>

    @Query("""SELECT m.* FROM mappings m JOIN items i ON m.item=i.id
        WHERE (:source='' OR i.source=:source)""")
    fun sourceMappings(source: String): Flow<List<Mapping>>

    @Query("SELECT * FROM mappings") suspend fun backupMappings(): List<Mapping>

    @Query("""SELECT n.* FROM epgNames n JOIN feeds f ON n.feed=f.id
        WHERE (:source='' OR f.source=:source)""")
    fun sourceNames(source: String): Flow<List<GuideName>>

    @Query("""SELECT DISTINCT p.feed,p.channel,f.source FROM programmes p JOIN feeds f ON p.feed=f.id
        WHERE (:source='' OR f.source=:source)""")
    fun programmeChannels(source: String): Flow<List<ProgrammeChannel>>

    // Resolve the feed/channel once in GuideStore. One range probe per feed for the
    // composed page replaces the per-card OR and correlated name/presence subqueries.
    @Query(GUIDE_PAGE_SQL)
    fun pageSlots(feed: String, channels: List<String>, from: Long, until: Long): Flow<List<Slot>>

    @Upsert suspend fun map(mapping: Mapping)

    @Query("DELETE FROM mappings WHERE item=:id") suspend fun unmap(id: String)

    @Query("SELECT * FROM mappings WHERE item=:id") fun mapping(id: String): Flow<Mapping?>

    @Query(
        """SELECT DISTINCT n.* FROM epgNames n JOIN feeds f ON n.feed=f.id WHERE f.source=:source
        AND instr(n.name,:query)>0 ORDER BY n.name,n.channel LIMIT 80"""
    )
    fun names(source: String, query: String): Flow<List<GuideName>>

    @Upsert fun put(feed: Feed)

    @Upsert fun put(names: List<GuideName>)

    @Upsert fun slots(rows: List<Slot>)

    @Query("DELETE FROM programmes WHERE feed=:feed") fun clear(feed: String)

    @Query("DELETE FROM programmes WHERE feed=:feed AND (end<=:from OR start>=:until)")
    fun pruneWindow(feed: String, from: Long, until: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM programmes WHERE feed=:feed AND (end<=:from OR start>=:until))")
    fun hasOutsideWindow(feed: String, from: Long, until: Long): Boolean

    @Query("DELETE FROM epgNames WHERE feed=:feed") fun clearNames(feed: String)

    @Query("SELECT * FROM feeds WHERE source=:source") suspend fun feeds(source: String): List<Feed>

    @Query(
        """SELECT programmes.* FROM programmes JOIN feeds ON programmes.feed=feeds.id
        WHERE feeds.source=:source AND (:feed='' OR feeds.id=:feed) AND programmes.end>:start AND programmes.start<:end
        AND (channel = NULLIF(:channel,'') OR (:channel='' OR NOT EXISTS
            (SELECT 1 FROM programmes p WHERE p.feed=feeds.id AND p.channel=:channel)) AND channel =
            (SELECT CASE WHEN COUNT(DISTINCT channel)=1 THEN MIN(channel) END FROM epgNames n WHERE n.feed=feeds.id AND name=:name))
        AND instr(search,:query)>0 ORDER BY programmes.start, programmes.feed LIMIT 160"""
    )
    fun guide(
        source: String,
        channel: String,
        name: String,
        start: Long,
        end: Long,
        query: String,
        feed: String = "",
    ): Flow<List<Slot>>
}

internal const val GUIDE_PAST_HOURS = 12L
internal const val GUIDE_FUTURE_DAYS = 3L

internal fun guideWindow(now: Long) =
    (now - java.util.concurrent.TimeUnit.HOURS.toMillis(GUIDE_PAST_HOURS)) to
        (now + java.util.concurrent.TimeUnit.DAYS.toMillis(GUIDE_FUTURE_DAYS))

// Count all valid programmes so an otherwise valid feed outside the window can replace old data.
internal fun readGuide(input: java.io.InputStream, now: Long, channel: (String, String) -> Unit, emit: (Programme) -> Unit) {
    readGuide(input, now, channel, {}, emit)
}

private fun readGuide(input: java.io.InputStream, now: Long, channel: (String, String) -> Unit,
    checkActive: () -> Unit, emit: (Programme) -> Unit) {
    val (from, until) = guideWindow(now)
    var total = 0
    XmlTv.read(input, channel) { row ->
        checkActive()
        if (++total > 1_000_000) throw SourceFailure("Rehber program sınırını aştı.")
        if (row.end > from && row.start < until) emit(row)
    }
    if (total == 0) throw SourceFailure("Rehberde geçerli program bulunamadı.")
}

internal fun populateGuide(db: LibraryDb, feed: Feed, input: java.io.InputStream, now: Long = System.currentTimeMillis(),
    checkActive: () -> Unit = {}) {
    val dao = db.guide()
    dao.put(feed)
    dao.clear(feed.id)
    dao.clearNames(feed.id)
    val names = ArrayList<GuideName>(128)
    val slots = ArrayList<Slot>(128)
    readGuide(
        input,
        now,
        { id, name ->
            checkActive()
            names += GuideName(feed.id, id, searchable(name))
            if (names.size == 128) {
                dao.put(names)
                names.clear()
            }
        },
        checkActive,
        { row ->
            slots +=
                Slot(
                    feed.id,
                    row.channel,
                    row.start,
                    row.end,
                    row.title,
                    row.description,
                    searchable(row.title),
                )
            if (slots.size == 128) {
                dao.slots(slots)
                slots.clear()
            }
        },
    )
    dao.put(names)
    dao.slots(slots)
}
