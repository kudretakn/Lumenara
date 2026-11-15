package dev.lumenara.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import dev.lumenara.catalog.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SourceSpec(
    val address: String,
    val allowHttp: Boolean,
    val user: String = "",
    val password: String = "",
) {
    fun encode() =
        JSONObject()
            .put("address", address)
            .put("http", allowHttp)
            .put("user", user)
            .put("pass", password)
            .toString()

    companion object {
        fun decode(value: String) =
            JSONObject(value).let {
                SourceSpec(
                    it.getString("address"),
                    it.getBoolean("http"),
                    it.getString("user"),
                    it.getString("pass"),
                )
            }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Repository private constructor(private val context: Context) {
    private val db = Room.databaseBuilder(context, LibraryDb::class.java, "lumenara.db")
        .addMigrations(LibraryDb.MIGRATION_7_8).build()
    val library = db.library()
    val guide = db.guide()
    private val vault = Vault(context)
    private val metadata = Metadata(context, vault)
    private val preferences = Preferences(context)
    // Resolve names from the complete catalog, before applying any visibility filter.
    val hidden: Flow<List<String>> = combine(
        preferences.showAdult, library.categoryCounts("", ""),
    ) { show, groups ->
        if (show) emptyList() else groups.map { it.category }.filter(::isAdultCategory)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun browse(source: String, kind: String, category: String, query: String, shelf: String, limit: Int,
               year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) =
        hidden.flatMapLatest { library.browse(source, kind, category, query, shelf, limit, year, genre, language, minRating, it) }

    fun shelfCount(source: String, kind: String, shelf: String) =
        hidden.flatMapLatest { library.shelfCount(source, kind, shelf, it) }

    fun categoryCounts(kind: String, source: String) =
        hidden.flatMapLatest { library.categoryCounts(kind, source, it) }
    fun categories(kind: String, source: String) =
        hidden.flatMapLatest { library.categories(kind, source, it) }
    fun liveCategoryCounts(source: String) =
        hidden.flatMapLatest { library.liveCategoryCounts(source, it) }
    fun liveByIds(source: String, ids: List<String>) =
        hidden.flatMapLatest { library.liveByIds(source, ids, it) }
    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val liveOrders = mutableMapOf<String, StateFlow<List<LiveChannelId>>>()
    // Room invalidation refreshes imports/deletions; hidden changes restart the filtered query.
    // Keep the flow alive between screens, so each source is queried just once per invalidation.
    fun liveChannelOrder(source: String): StateFlow<List<LiveChannelId>> = synchronized(liveOrders) {
        liveOrders.getOrPut(source) {
            hidden.flatMapLatest { library.liveChannelOrder(source, it) }
                .distinctUntilChanged().stateIn(liveScope, SharingStarted.Lazily, emptyList())
        }
    }
    suspend fun liveChannel(number: Int, source: String): Item? {
        if (number < 1) return null
        val visible = hidden.first()
        val selectedSource = source.ifEmpty {
            library.snapshot().firstOrNull {
                library.liveAtOffset(it.id, 0, visible) != null
            }?.id ?: return null
        }
        return library.liveAtOffset(selectedSource, number - 1, visible)
    }
    fun livePage(source: String, category: String, query: String, shelf: String, limit: Int, offset: Int) =
        hidden.flatMapLatest { library.livePage(source, category, query, shelf, limit, offset, it) }
    fun liveCount(source: String, category: String = "", query: String = "", shelf: String = "") =
        hidden.flatMapLatest { library.liveCount(source, category, query, shelf, it) }
    suspend fun liveIndex(source: String, id: String, category: String = "", query: String = "", shelf: String = "") =
        library.liveIndex(source, id, category, query, shelf, hidden.first())
    fun episodes(id: String) = hidden.flatMapLatest { library.episodes(id, it) }
    suspend fun episodeSnapshot(id: String) = library.episodeSnapshot(id, hidden.first())
    fun continueWatching(source: String) =
        hidden.flatMapLatest { library.continueWatching(source, it) }
    fun recentLive(source: String) = preferences.recentLive.flatMapLatest { ids ->
        liveByIds(source, ids).map { rows ->
            val byId = rows.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }
    }
    fun nowAiring(source: String, now: Long, query: String = "") =
        hidden.flatMapLatest { guide.nowAiring(source, now, query, it) }
    fun searchPrograms(source: String, query: String, from: Long, now: Long) =
        hidden.flatMapLatest { guide.searchPrograms(source, query, from, now, it) }

    val subtitles = SubtitleService(preferences, SubtitleCache(java.io.File(context.cacheDir, "subtitles")))

    suspend fun production(item: Item): Item = if (item.kind == "EPISODE") library.item(item.parent) ?: item
        else library.item(item.id) ?: item

    private val versionIndex = java.util.concurrent.ConcurrentHashMap<String, Map<String, List<Item>>>()

    suspend fun versions(item: Item): List<Item> {
        val all = allVersions(item)
        return if (preferences.showAdult.first()) all else all.filterNot { isAdultCategory(it.category) }
    }

    private suspend fun allVersions(item: Item): List<Item> = withContext(Dispatchers.IO) {
        val base = production(item)
        if (base.kind !in listOf("MOVIE", "SERIES")) return@withContext listOf(base)
        if (base.versionKey.isNotEmpty()) return@withContext library.versionRows(base.versionKey)
        val key = "${base.source}|${base.kind}|${base.generation}"
        val index = versionIndex[key] ?: library.versionCandidates(base.source, base.kind).versionGroups()
            .flatMap { group -> group.map { it.id to group } }.toMap().also { versionIndex[key] = it }
        index[base.id] ?: listOf(base)
    }

    suspend fun preferenceKey(item: Item): String {
        val base = production(item)
        val suffix = if (item.kind == "EPISODE") item.seriesEpisode().let { "|${it.season}|${it.episode}" } else ""
        return versionPreferenceIdentity(base) + suffix
    }

    private suspend fun versionPreferenceIdentity(item: Item): String =
        allVersions(item).minByOrNull { it.id }?.preferenceIdentity() ?: production(item).preferenceIdentity()

    suspend fun selectedVersion(item: Item): Item {
        if (!preferences.showAdult.first() &&
            (isAdultCategory(item.category) || isAdultCategory(production(item).category)))
            throw SourceFailure("Bu kategori gizli.")
        if (item.kind !in listOf("MOVIE", "SERIES", "EPISODE")) return item
        val base = production(item)
        val choices = versions(base)
        val preferred = preferences.text("version_${versionPreferenceIdentity(base)}").first()
        val version = choices.firstOrNull { productionName(it.title, it.year).version.name == preferred }
            ?: choices.firstOrNull { productionName(it.title, it.year).version == AudioVersion.DUBBED }
            ?: choices.firstOrNull { it.id == base.id } ?: base
        return resolveVersion(item, version)
    }

    suspend fun chooseVersion(item: Item, version: Item): Item {
        val resolved = resolveVersion(item, version)
        preferences.set("version_${versionPreferenceIdentity(item)}", productionName(version.title, version.year).version.name)
        return resolved
    }

    private suspend fun resolveVersion(item: Item, version: Item): Item {
        if (item.kind != "EPISODE") return version
        if (item.parent == version.id) return item
        val number = item.seriesEpisode()
        val showAdult = preferences.showAdult.first()
        return expand(version).firstOrNull {
            val candidate = it.seriesEpisode()
            (showAdult || !isAdultCategory(it.category)) &&
                candidate.season == number.season && candidate.episode == number.episode
        } ?: throw SourceFailure("Bu bölüm seçilen sürümde bulunamadı.")
    }

    suspend fun subtitleQuery(item: Item): SubtitleQuery = withContext(Dispatchers.IO) {
        val base = production(item)
        val name = productionName(base.title, base.year)
        var query = SubtitleQuery(name.title, name.year, base.tmdbId())
        val source = library.source(base.source)
        if (source != null) {
            val spec = SourceSpec.decode(vault.decrypt(source.endpoint))
            if (spec.user.isNotBlank()) {
                try {
                    query = Xtream(spec).subtitleInfo(address(base), base.kind, name.title, name.year)
                    if (query.tmdb.isNotBlank() && base.tmdbId() != query.tmdb) {
                        db.withTransaction {
                            library.save(listOf(base.copy(search = base.search.replace(Regex(" \\[tmdb:[0-9]+\\]"), "") + " [tmdb:${query.tmdb}]")))
                            // A newly learned ID can join translated titles or split conflicting IDs.
                            library.assignVersions(library.versionCandidates(base.source, base.kind).versionAssignments())
                        }
                        versionIndex.keys.removeAll { it.startsWith("${base.source}|") }
                    }
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* A title/year search remains available when provider info fails. */ }
            }
        }
        if (item.kind == "EPISODE") item.seriesEpisode().let { query.copy(season = it.season, episode = it.episode) }
        else query
    }

    suspend fun details(item: Item) =
        metadata.details(item).also {
            if (it.collection.isNotBlank())
                db.withTransaction {
                    if (library.exists(item.id)) library.collection(Member(item.id, it.collection))
                }
        }

    suspend fun configureTmdb(token: String) =
        withContext(Dispatchers.IO) { metadata.configure(token) }

    fun tmdbConfigured() = metadata.userConfigured()

    fun tmdbBuiltIn() = metadata.hasBuiltInToken

    private val lock = Mutex()

    companion object {
        @Volatile private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance
                ?: synchronized(this) {
                    instance ?: Repository(context.applicationContext).also { instance = it }
                }
    }

    suspend fun add(name: String, spec: SourceSpec) =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { "Kaynak adını girin." }
            lock.withLock {
                val source =
                    Source(
                        UUID.randomUUID().toString(),
                        name.trim(),
                        vault.encrypt(spec.encode()),
                        spec.allowHttp,
                        0,
                        0,
                    )
                import(source, spec)
            }
        }

    suspend fun refresh(id: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val source = library.source(id) ?: return@withLock
                import(source, SourceSpec.decode(vault.decrypt(source.endpoint)))
            }
        }

    suspend fun remove(id: String) = lock.withLock {
        library.deleteSource(id)
        versionIndex.keys.removeAll { it.startsWith("$id|") }
    }

    suspend fun addGuide(sourceId: String, url: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val source = library.source(sourceId) ?: throw SourceFailure("Kaynak bulunamadı.")
                val address = url.ifBlank {
                    val spec = SourceSpec.decode(vault.decrypt(source.endpoint))
                    if (spec.user.isEmpty()) throw SourceFailure("XMLTV adresini girin.")
                    Xtream(spec).guideAddress()
                }
                syncGuide(
                    source,
                    Feed(contentKey(sourceId, address), sourceId, vault.encrypt(address)),
                )
            }
        }

    suspend fun refreshGuides(sourceId: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val source = library.source(sourceId) ?: return@withLock
                for (feed in guide.feeds(sourceId)) syncGuide(source, feed)
            }
        }

    private suspend fun syncGuide(source: Source, feed: Feed) = db.withTransaction {
        Transport.read(vault.decrypt(feed.encryptedAddress), source.allowHttp) { input, _ ->
            val buffered = input.buffered()
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val xml =
                if (gzip) bounded(java.util.zip.GZIPInputStream(buffered), 256L * 1024 * 1024)
                else buffered
            populateGuide(db, feed, xml)
        }
    }

    private suspend fun import(source: Source, spec: SourceSpec) {
        val token = maxOf(System.currentTimeMillis(), source.refreshed + 1)
        val staged = CatalogStage(context.cacheDir, source.id, token)
        var providerLimit = 0
        try {
            staged.write { append ->
                val grouper = SeriesGrouper()
                fun accept(row: Listing) {
                    if (spec.user.isNotEmpty()) append(item(source.id, token, row))
                    else grouper.accept(row) { append(item(source.id, token, it)) }
                }
                when {
                    spec.user.isNotEmpty() -> providerLimit = Xtream(spec).read(::accept)
                    spec.address.startsWith("content://") ->
                        context.contentResolver.openInputStream(Uri.parse(spec.address))?.use {
                            Playlist.read(
                                bounded(it).bufferedReader(),
                                null,
                                spec.allowHttp,
                                ::accept,
                            )
                        }
                    else ->
                        Transport.read(spec.address, spec.allowHttp) { input, finalUrl ->
                            Playlist.read(
                                input.bufferedReader(),
                                URI(finalUrl),
                                spec.allowHttp,
                                ::accept,
                            )
                        }
                }
            }
            if (providerLimit > 0)
                Preferences(context).set("provider_${source.id}", providerLimit.toString())
            db.withTransaction {
                library.save(source)
                staged.read { batch ->
                    library.writeBatch(batch.map { it.copy(versionKey = it.id, displayTitle = it.title, displayYear = it.year) })
                }
                if (spec.user.isEmpty()) library.prunePlaylist(source.id, token)
                else library.prune(source.id, token)
                library.orphans(source.id)
                for (kind in listOf("MOVIE", "SERIES")) {
                    val rows = library.versionCandidates(source.id, kind)
                    // Resolve across the complete source, not each 128-row staging batch: matching
                    // translated titles and a missing TMDB ID may occur in distant batches.
                    library.assignVersions(rows.versionAssignments())
                    rows.versionGroups().filter { it.size > 1 }.forEach { versions ->
                        val marks = versions.mapNotNull { library.mark(it.id) }
                        val newest = marks.maxByOrNull { it.updated }
                        if (newest != null) {
                            val shared = newest.copy(favorite = marks.any { it.favorite }, listed = marks.any { it.listed })
                            versions.forEach { library.save(shared.copy(id = it.id)) }
                        }
                    }
                }
                library.save(source.copy(refreshed = token, count = library.count(source.id)))
            }
            versionIndex.keys.removeAll { it.startsWith("${source.id}|") }
        } finally {
            staged.close()
        }
    }

    private fun item(source: String, token: Long, entry: Listing) =
        Item(
            contentKey(source, entry.url),
            source,
            entry.title,
            entry.group,
            entry.kind.name,
            entry.tvgId,
            vault.encrypt(entry.url),
            searchable("${entry.title} ${entry.group} ${entry.tvgId}") +
                (entry.tmdbId.toLongOrNull()?.takeIf { it > 0 }?.let { " [tmdb:$it]" } ?: ""),
            token,
            entry.logo.takeIf { it.startsWith("https://") }?.let(vault::encrypt).orEmpty(),
            parent = entry.parentUrl.takeIf(String::isNotBlank)?.let { contentKey(source, it) }.orEmpty(),
            summary = entry.summary,
            year = entry.year,
            genre = entry.genre,
            rating = entry.rating,
            language = entry.language,
            castNames = entry.cast,
            archive =
                entry.archive
                    .takeIf { it.isNotEmpty() && entry.days > 0 }
                    ?.let(vault::encrypt)
                    .orEmpty(),
            archiveDays = entry.days,
            archiveZone = entry.zone,
            introStartMs = entry.introStartMs,
            introEndMs = entry.introEndMs,
            backdrop =
                entry.backdrop.takeIf { it.startsWith("https://") }?.let(vault::encrypt).orEmpty(),
        ).let { it.copy(versionKey = it.id, displayTitle = it.title, displayYear = it.year) }

    suspend fun replay(channel: Item, slot: Slot): Item =
        withContext(Dispatchers.IO) {
            val source = library.source(channel.source) ?: throw SourceFailure("Kaynak bulunamadı.")
            val address =
                Catchup.address(
                    vault.decrypt(channel.archive),
                    channel.archiveDays,
                    slot.start,
                    slot.end,
                    System.currentTimeMillis(),
                    channel.archiveZone,
                    source.allowHttp,
                ) ?: throw SourceFailure("Program sağlayıcının arşiv aralığında değil.")
            channel.copy(
                title = "${channel.title} · ${slot.title}",
                encryptedUrl = vault.encrypt(address),
                kind = "REPLAY",
            )
        }

    suspend fun expand(series: Item): List<Item> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val source = library.source(series.source) ?: return@withLock emptyList()
                val spec = SourceSpec.decode(vault.decrypt(source.endpoint))
                if (spec.user.isEmpty()) return@withLock library.episodeSnapshot(series.id)
                val token = System.currentTimeMillis()
                val listings =
                    Xtream(spec)
                        .episodes(vault.decrypt(series.encryptedUrl), series.title)
                        .distinctBy { it.url }
                val entries = listings.map { item(source.id, token, it).copy(parent = series.id) }
                db.withTransaction {
                    library.save(entries)
                    listings.zip(entries).forEach { (listing, episode) ->
                        val mark = library.mark(episode.id) ?: Mark(episode.id)
                        if (mark.duration <= 0 && listing.durationMs > 0)
                            library.save(mark.copy(duration = listing.durationMs))
                    }
                    library.pruneEpisodes(series.id, token)
                }
                for (entry in entries) {
                    if (library.mark(entry.id)?.updated == 0L || library.mark(entry.id) == null) {
                        val number = entry.seriesEpisode()
                        val shared = allVersions(series).filter { it.id != series.id }
                            .flatMap { library.episodeSnapshot(it.id) }.filter {
                                val n = it.seriesEpisode(); n.season == number.season && n.episode == number.episode
                            }.mapNotNull { library.mark(it.id) }.maxByOrNull { it.updated }
                        if (shared != null) library.save(shared.copy(id = entry.id))
                    }
                }
                entries
            }
        }

    suspend fun address(item: Item) =
        withContext(Dispatchers.IO) { vault.decrypt(item.encryptedUrl) }

    suspend fun poster(item: Item) =
        withContext(Dispatchers.IO) { item.logo.takeIf(String::isNotEmpty)?.let(vault::decrypt) }

    suspend fun backdrop(item: Item) =
        withContext(Dispatchers.IO) {
            item.backdrop.takeIf(String::isNotEmpty)?.let(vault::decrypt)
        }

    suspend fun updateMark(id: String, transform: (Mark) -> Mark) = db.withTransaction {
        val item = library.item(id) ?: return@withTransaction
        val siblings = if (item.kind == "EPISODE") {
            val number = item.seriesEpisode()
            allVersions(item).flatMap { library.episodeSnapshot(it.id) }.filter {
                val n = it.seriesEpisode(); n.season == number.season && n.episode == number.episode
            }
        } else if (item.kind in listOf("MOVIE", "SERIES")) allVersions(item) else listOf(item)
        val existing = siblings.mapNotNull { library.mark(it.id) }
        val shared = library.mark(id) ?: existing.maxByOrNull { it.updated }?.copy(id = id) ?: Mark(id)
        val result = transform(shared)
        (siblings + item).distinctBy { it.id }.forEach { library.save(result.copy(id = it.id)) }
    }

    suspend fun schedule(hours: Long, checkNow: Boolean = false) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            PeriodicWorkRequestBuilder<RefreshWorker>(hours, TimeUnit.HOURS)
                .setInitialDelay(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork("iptv", ExistingPeriodicWorkPolicy.UPDATE, request)
        if (
            checkNow &&
                library.snapshot().any {
                    System.currentTimeMillis() - it.refreshed >= TimeUnit.HOURS.toMillis(hours)
                }
        ) {
            manager.enqueueUniqueWork(
                "startup-refresh",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf("staleHours" to hours))
                    .build(),
            )
        }
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repository.get(applicationContext)
        var failed = false
        val prefs = Preferences(applicationContext)
        for (source in repo.library.snapshot()) {
            val threshold = inputData.getLong("staleHours", 0)
            if (
                threshold > 0 &&
                    runAttemptCount == 0 &&
                    System.currentTimeMillis() - source.refreshed <
                        TimeUnit.HOURS.toMillis(threshold)
            )
                continue
            try {
                repo.refresh(source.id)
                repo.refreshGuides(source.id)
                prefs.set("sync_${source.id}", "Güncel")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
                prefs.set("sync_${source.id}", "Yenileme başarısız")
            }
        }
        return when {
            !failed -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }
}
