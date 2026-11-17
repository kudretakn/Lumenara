package dev.lumenara.tv

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.SystemClock
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import dev.lumenara.catalog.episodeName
import dev.lumenara.catalog.watched
import dev.lumenara.data.*
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

internal data class WatchNextPlayback(val itemId: String, val position: Long)

internal fun parseWatchNextLink(value: String): WatchNextPlayback? = runCatching {
    val uri = URI(value)
    if (uri.scheme != "lumenara" || uri.rawAuthority != "play" || uri.fragment != null) return null
    val path = uri.rawPath ?: return null
    if (!path.startsWith('/') || path.drop(1).contains('/')) return null
    val id = URLDecoder.decode(path.drop(1).replace("+", "%2B"), "UTF-8")
    if (id.isBlank()) return null
    val query = uri.rawQuery ?: return null
    if (!query.matches(Regex("position=[0-9]+"))) return null
    val position = query.substringAfter('=').toLongOrNull() ?: return null
    WatchNextPlayback(id, position)
}.getOrNull()

internal data class WatchNextEntry(
    val item: Item,
    val mark: Mark,
    val next: Boolean = false,
    val seriesTitle: String = "",
)

/** A completed episode advances across seasons; a completed movie has no successor. */
internal fun watchNextEntry(item: Item, mark: Mark, episodes: List<Item> = emptyList(),
                            marks: List<Mark> = emptyList(), seriesTitle: String = ""): WatchNextEntry? {
    if (item.kind !in listOf("MOVIE", "EPISODE") || mark.position <= 0) return null
    val complete = if (item.kind == "EPISODE") watched(mark.position, mark.duration)
        else mark.duration > 0 && mark.position >= mark.duration * .95
    if (!complete) return WatchNextEntry(item, mark, seriesTitle = seriesTitle)
    if (item.kind != "EPISODE") return null
    val byId = marks.associateBy { it.id }
    val ordered = episodes.seriesOrder()
    val index = ordered.indexOfFirst { it.id == item.id }
    if (index < 0) return null
    val next = ordered.drop(index + 1).firstOrNull {
        val m = byId[it.id]
        m == null || !watched(m.position, m.duration)
    } ?: return null
    val saved = byId[next.id] ?: Mark(next.id)
    return WatchNextEntry(next, saved.copy(updated = mark.updated),
        next = saved.position <= 0, seriesTitle = seriesTitle)
}

/** Application-owned scope keeps a trailing update alive when the player/activity closes. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WatchNext private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo = Repository.get(context)
    private val prefs = Preferences(context)
    private val state = context.getSharedPreferences("watch_next_state", Context.MODE_PRIVATE)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val uri = TvContractCompat.WatchNextPrograms.CONTENT_URI
    private var started = false
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) { requests.trySend(Unit) }
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        // Some launchers/devices have no TV provider. Playback must still work.
        runCatching { context.contentResolver.registerContentObserver(uri, true, observer) }
        scope.launch {
            combine(repo.library.watchNextMarks(), repo.hidden,
                prefs.text("watch_next", "Açık")) { _, _, _ -> Unit }
                .collect { requests.trySend(Unit) }
        }
        scope.launch {
            var previous: Long? = null
            for (ignored in requests) {
                previous?.let { delay((30_000 - (SystemClock.elapsedRealtime() - it)).coerceAtLeast(0)) }
                try {
                    Perf.mark("startup_watch_next_publish")
                    sync()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Unsupported or temporarily unavailable TV provider. */ }
                finally { Perf.since("startup_watch_next_publish", "startup_watch_next_publish_complete") }
                previous = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun excluded(): Set<String> = state.getStringSet("excluded", emptySet()).orEmpty().toSet()

    internal suspend fun exclude(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            state.edit().putStringSet("excluded", excluded() + id).commit()
        }
        requests.trySend(Unit)
        Unit
    }

    private val mutex = kotlinx.coroutines.sync.Mutex()

    internal suspend fun sync() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val resolver = context.contentResolver
            val redirects = repo.library.redirects().associate { it.oldId to it.newId }
            data class Published(val row: Long, val id: String, val browsable: Boolean)
            val published = mutableListOf<Published>()
            // Query all columns: provider owns browsable and it must never be overwritten by us.
            val cursor = resolver.query(uri, null, null, null, null) ?: return@withLock
            cursor.use {
                val row = it.getColumnIndexOrThrow("_id")
                // content_id is the public Watch Next field we own; internal_provider_id
                // is restricted to the system TV provider.
                val id = it.getColumnIndexOrThrow("content_id")
                val browse = it.getColumnIndexOrThrow("browsable")
                val owner = it.getColumnIndexOrThrow("package_name")
                while (it.moveToNext()) {
                    if (it.getString(owner) == context.packageName && !it.isNull(id))
                        published += Published(it.getLong(row), redirects[it.getString(id)] ?: it.getString(id), it.getInt(browse) != 0)
                }
            }
            val blocked = excluded().map { redirects[it] ?: it }.toSet() + published.filterNot { it.browsable }.map { it.id }
            // Persist before deletion so disabled cards remain excluded after toggles/restarts.
            if (blocked != excluded()) state.edit().putStringSet("excluded", blocked).commit()
            val desired = if (prefs.text("watch_next", "Açık").first() == "Açık") entries(blocked)
                else emptyList()
            val desiredIds = desired.map { it.item.id }.toSet()
            published.filter { !it.browsable || it.id !in desiredIds }.forEach {
                resolver.delete(TvContractCompat.buildWatchNextProgramUri(it.row), null, null)
            }
            val existing = published.filter { it.browsable && it.id in desiredIds }.associateBy { it.id }
            published.filter { it.browsable && it.id in desiredIds && existing[it.id]?.row != it.row }.forEach {
                resolver.delete(TvContractCompat.buildWatchNextProgramUri(it.row), null, null)
            }
            desired.forEach { entry ->
                val program = program(entry)
                val values = program.toContentValues()
                val old = existing[entry.item.id]
                if (old == null) resolver.insert(uri, values)
                else {
                    // Avoid provider notification loops and unnecessary writes.
                    val rowUri = TvContractCompat.buildWatchNextProgramUri(old.row)
                    resolver.query(rowUri, null, null, null, null)?.use {
                        if (it.moveToFirst() && WatchNextProgram.fromCursor(it).hasAnyUpdatedValues(program))
                            resolver.update(rowUri, values, null, null)
                    }
                }
            }
        }
    }

    private suspend fun entries(blocked: Set<String>): List<WatchNextEntry> {
        val hidden = repo.hidden.first()
        val marks = repo.library.watchNextMarks().first()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<WatchNextEntry>()
        for (mark in marks) {
            val item = repo.library.item(mark.id) ?: continue
            if (item.category in hidden || item.id in blocked || item.parent in blocked) continue
            if (item.kind == "MOVIE") {
                watchNextEntry(item, mark)?.let { result += it }
            } else {
                val series = repo.library.item(item.parent) ?: continue
                if (series.category in hidden || !seen.add(series.id)) continue
                val episodes = repo.episodeSnapshot(series.id)
                watchNextEntry(item, mark, episodes, marks, series.title)?.let {
                    if (it.item.id !in blocked) result += it
                }
            }
        }
        return result
    }

    // The public WatchNextProgram.Builder setters are inherited from BasePreviewProgram.Builder,
    // which tvprovider marks @RestrictTo(LIBRARY_GROUP); lint then flags every documented setter.
    // Known false positive (same calls as the official Android TV samples).
    @android.annotation.SuppressLint("RestrictedApi")
    private suspend fun program(entry: WatchNextEntry): WatchNextProgram {
        val item = entry.item
        val mark = entry.mark
        val position = if (entry.next) 0 else mark.position
        val link = Uri.Builder().scheme("lumenara").authority("play").appendPath(item.id)
            .appendQueryParameter("position", position.toString()).build()
        val builder = WatchNextProgram.Builder()
            .setContentId(item.id)
            .setWatchNextType(if (entry.next) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setType(if (item.kind == "EPISODE") TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
            .setTitle(if (item.kind == "EPISODE") entry.seriesTitle else item.title)
            .setIntentUri(link)
            .setLastEngagementTimeUtcMillis(mark.updated)
            .setLastPlaybackPositionMillis(position.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .setDurationMillis(mark.duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        val remaining = (mark.duration - position).coerceAtLeast(0)
        builder.setDescription(if (mark.duration > 0) "${(remaining + 59_999) / 60_000} dk kaldı" else item.summary)
        (repo.poster(item) ?: repo.poster(repo.production(item)))?.takeIf { it.isNotBlank() }
            ?.let { builder.setPosterArtUri(Uri.parse(it)) }
        if (item.kind == "EPISODE") {
            val name = episodeName(item.title, item.category)
            builder.setEpisodeTitle("S${name.season} B${name.episode} · ${name.title}")
                .setSeasonNumber(name.season).setEpisodeNumber(name.episode)
        }
        return builder.build()
    }

    companion object {
        @Volatile private var instance: WatchNext? = null
        fun get(context: Context): WatchNext = instance ?: synchronized(this) {
            instance ?: WatchNext(context.applicationContext).also { instance = it }
        }
        fun removedFromList(id: String) {
            instance?.let { publisher -> publisher.scope.launch { publisher.exclude(id) } }
        }
    }
}
