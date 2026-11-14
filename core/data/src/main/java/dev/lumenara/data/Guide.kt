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
    indices = [Index("feed", "channel", "end")],
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

@Dao
interface GuideDao {
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

internal fun populateGuide(db: LibraryDb, feed: Feed, input: java.io.InputStream) {
    val dao = db.guide()
    dao.put(feed)
    dao.clear(feed.id)
    dao.clearNames(feed.id)
    val names = ArrayList<GuideName>(128)
    val slots = ArrayList<Slot>(128)
    var total = 0
    XmlTv.read(
        input,
        { id, name ->
            names += GuideName(feed.id, id, searchable(name))
            if (names.size == 128) {
                dao.put(names)
                names.clear()
            }
        },
        { row ->
            if (++total > 1_000_000) throw SourceFailure("Rehber program sınırını aştı.")
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
    if (total == 0) throw SourceFailure("Rehberde geçerli program bulunamadı.")
    dao.put(names)
    dao.slots(slots)
}
