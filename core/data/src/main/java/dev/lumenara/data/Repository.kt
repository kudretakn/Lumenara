package dev.lumenara.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.InvalidationTracker
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

data class LiveWindow(val rows: List<Item>, val revision: Long, val hidden: List<String>)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Repository private constructor(private val context: Context) {
    private val db = Room.databaseBuilder(context, LibraryDb::class.java, "lumenara.db")
        .addMigrations(LibraryDb.MIGRATION_7_8, LibraryDb.MIGRATION_8_9, LibraryDb.MIGRATION_9_10,
            LibraryDb.MIGRATION_10_11)
        .build()
    val library = db.library()
    val guide = db.guide()
    private val vault = Vault(context)
    private val metadata = Metadata(context, vault)
    private val preferences = Preferences(context)
    private val restoreTransaction = RestoreTransaction(object : RestoreStorage {
        override suspend fun pending() = db.restoreJournal().pending()
        override suspend fun snapshot(): String = vault.encrypt(JSONObject()
            .put("settings", preferences.snapshotSettings())
            .put("tmdb", metadata.encryptedToken() ?: JSONObject.NULL).toString())
        override suspend fun savePending(snapshot: String) {
            db.restoreJournal().save(RestorePending(snapshot = snapshot))
            // Android WAL normally uses synchronous=NORMAL. A FULL checkpoint fsyncs the undo
            // record before DataStore can publish anything, even if power is lost before Room
            // commits. A busy checkpoint aborts before restore writes; the journal remains safe.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "Geri yükleme günlüğü kaydedilemedi." }
            }
        }
        override suspend fun restoreSnapshot(snapshot: String) {
            val old = JSONObject(vault.decrypt(snapshot))
            preferences.restoreSnapshot(old.getString("settings"))
            metadata.restoreEncryptedToken(if (old.isNull("tmdb")) null else old.getString("tmdb"))
        }
        override suspend fun clearPending() = db.restoreJournal().clear()
        override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
    })
    // Constructors and workers must not expose Room/preferences until crash recovery finishes.
    init { kotlinx.coroutines.runBlocking(Dispatchers.IO) {
        StartupPerf.measure("startup_room_open") { db.openHelper.writableDatabase }
        StartupPerf.measure("startup_room_recovery") { restoreTransaction.recover() }
    } }
    private val catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val catalog = CatalogStreams(library.categoryNames(), library::categoryCounts,
        library::sourceCategoryCounts, preferences.showAdult, catalogScope)
    val hidden: Flow<List<String>> = catalog.hidden
    private val seriesExpansionCache = SeriesExpansionCache(
        readStamp = { preferences.text("series_expanded_$it").first().toLongOrNull() ?: 0L },
        writeStamp = { id, stamp -> preferences.set("series_expanded_$id", stamp.toString()) },
    )

    fun browse(source: String, kind: String, category: String, query: String, shelf: String, limit: Int,
               year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) =
        hidden.flatMapLatest { library.browse(source, kind, category, query, shelf, limit, year, genre, language, minRating, it) }

    fun browsePage(source: String, kind: String, category: String, query: String, shelf: String,
        limit: Int, offset: Int, year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) =
        hidden.flatMapLatest { library.browsePage(source, kind, category, query, shelf, limit, offset,
            year, genre, language, minRating, it) }

    fun browseCount(source: String, kind: String, category: String, query: String, shelf: String,
        year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) =
        hidden.flatMapLatest { library.browseTotal(source, kind, category, query, shelf,
            year, genre, language, minRating, it) }

    suspend fun browseIndex(source: String, kind: String, category: String, query: String, shelf: String,
        id: String, year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) =
        library.browseIndex(source, kind, category, query, shelf, id, year, genre, language, minRating, hidden.first())

    fun browseKinds(source: String, kind: String, category: String, query: String, shelf: String,
        year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) =
        hidden.flatMapLatest { library.browseKinds(source, kind, category, query, shelf,
            year, genre, language, minRating, it) }

    fun shelfCount(source: String, kind: String, shelf: String) =
        hidden.flatMapLatest { library.shelfCount(source, kind, shelf, it) }

    fun categoryCounts(kind: String, source: String) = catalog.categoryCounts(kind, source)
    fun categories(kind: String, source: String) = categoryCounts(kind, source).map { groups -> groups.map { it.category } }
    fun liveCategoryCounts(source: String) =
        hidden.flatMapLatest { library.liveCategoryCounts(source, it) }
            .distinctUntilChanged()
    /** A bounded visible page, with no whole-catalog GuideResolver or future schedules. */
    fun liveNow(rows: List<Item>, now: Long): Flow<Map<String, Slot>> = flow {
        if (rows.isEmpty()) emit(emptyMap()) else emitAll(
            library.liveNow(rows, now).map { current ->
                current.groupBy { it.itemId }.mapValues { (_, hits) -> hits.first().slot }
            })
    }.flowOn(Dispatchers.Default)
    fun liveByIds(source: String, ids: List<String>) =
        hidden.flatMapLatest { library.liveByIds(source, ids, it) }
    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val liveOrders = mutableMapOf<String, StateFlow<List<LiveChannelId>>>()
    // Channel badges use demand-driven snapshots. Keep explicit order subscribers warm
    // briefly across navigation, then release the observed list and Room subscription.
    fun liveChannelOrder(source: String): StateFlow<List<LiveChannelId>> = synchronized(liveOrders) {
        liveOrders.getOrPut(source) {
            hidden.flatMapLatest { library.liveChannelOrder(source, it) }
                .distinctUntilChanged().stateIn(liveScope, SharingStarted.WhileSubscribed(
                    stopTimeoutMillis = 30_000, replayExpirationMillis = 0), emptyList())
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
    fun livePageAfter(source: String, category: String, query: String, shelf: String, limit: Int,
        after: LiveCursor?, backwards: Boolean = false) =
        hidden.flatMapLatest { library.livePageAfter(source, category, query, shelf, limit, after, backwards, it) }

    fun liveNeighbors(source: String, category: String, query: String, shelf: String, limit: Int,
        id: String, backwards: Boolean = false) =
        hidden.flatMapLatest { library.liveNeighbors(source, category, query, shelf, limit, id, backwards, it) }

    /** Keep absolute lazy-list indices correct if an import/mark/visibility change moves a boundary. */
    fun liveWindow(source: String, category: String, query: String, shelf: String, limit: Int,
        offset: Int, after: LiveWindowCursor?): Flow<LiveWindow> = hidden.flatMapLatest { visible ->
        flow {
            val cursor = after?.takeIf { it.revision == liveWindowEpoch(shelf) && it.hidden == visible }?.cursor
            var initial = true
            val rows = if (cursor == null) library.livePage(source, category, query, shelf, limit, offset, visible)
                else library.livePageAfter(source, category, query, shelf, limit, cursor, hidden = visible)
            rows.collect { page ->
                val current = if (initial || cursor == null) page
                    else library.livePage(source, category, query, shelf, limit, offset, visible).first()
                emit(LiveWindow(current, liveWindowEpoch(shelf), visible))
                initial = false
            }
        }
    }

    private val liveOrderEpoch = java.util.concurrent.atomic.AtomicLong()
    private val liveFavoriteEpoch = java.util.concurrent.atomic.AtomicLong()
    private fun liveWindowEpoch(shelf: String): Long =
        if (shelf == "favorite") liveFavoriteEpoch.get() else liveOrderEpoch.get()
    private data class NumberSnapshot(val epoch: Long, val hidden: List<String>, val numbers: Map<String, Int>)
    private val liveNumberSnapshots = java.util.concurrent.ConcurrentHashMap<String, NumberSnapshot>()
    private val liveNumberMutex = Mutex()
    private val liveInvalidation = object : InvalidationTracker.Observer("items", "marks") {
        override fun onInvalidated(tables: Set<String>) {
            if ("items" in tables) liveOrderEpoch.incrementAndGet()
            liveFavoriteEpoch.incrementAndGet()
        }
    }
    init { db.invalidationTracker.addObserver(liveInvalidation) }

    /** A demand-driven snapshot, not an always-subscribed sort on every item write.
     * Badges and number entry share source-local numbering. Only id/title (plus source when
     * unselected) are read, and the map is reused across page changes until invalidated.
     */
    fun liveChannelNumbers(source: String, ids: List<String>): Flow<Map<String, Int>> = flow {
        if (ids.isEmpty()) { emit(emptyMap()); return@flow }
        val visible = hidden.first()
        val numbers = liveNumberMutex.withLock {
            val epoch = liveOrderEpoch.get()
            val cached = liveNumberSnapshots[source]
            if (cached != null && cached.epoch == epoch && cached.hidden == visible) cached.numbers
            else {
                val order = library.liveChannelOrder(source, visible).first()
                val perSource = mutableMapOf<String, Int>()
                val result = order.associate { row ->
                    val number = (perSource[row.source] ?: 0) + 1
                    perSource[row.source] = number
                    row.id to number
                }
                liveNumberSnapshots[source] = NumberSnapshot(epoch, visible, result)
                result
            }
        }
        emit(ids.mapNotNull { id -> numbers[id]?.let { id to it } }.toMap())
    }.flowOn(Dispatchers.Default)

    fun liveCount(source: String, category: String = "", query: String = "", shelf: String = "") =
        hidden.flatMapLatest { library.liveCount(source, category, query, shelf, it) }
    suspend fun liveIndex(source: String, id: String, category: String = "", query: String = "", shelf: String = "") =
        library.liveIndex(source, id, category, query, shelf, hidden.first())
    fun episodes(id: String) = hidden.flatMapLatest { library.episodes(id, it) }
    suspend fun episodeSnapshot(id: String) = library.episodeSnapshot(id, hidden.first())
    // Visibility for history needs only marked cards, their parents and those parents' episodes.
    // Do not make the Hub wait for names/counts over the complete provider catalog.
    private val historyHidden = combine(preferences.showAdult, library.continueWatchingCategories()) { show, names ->
        if (show) emptyList() else names.filter(::isAdultCategory)
    }.distinctUntilChanged()
    fun continueWatching(source: String) =
        historyHidden.flatMapLatest { library.continueWatching(source, it) }
    fun recentLive(source: String) = preferences.recentLive.flatMapLatest { ids ->
        // Twenty primary-key probes need no full-catalog category-name query. Apply the same
        // visibility rule to these bounded rows, preserving DataStore's recency order.
        combine(library.liveByIds(source, ids), preferences.showAdult) { rows, showAdult ->
            val byId = rows.associateBy { it.id }
            visibleRecentIds(ids, rows.associate { it.id to it.category }, showAdult).mapNotNull { byId[it] }
        }
    }
    private val guideStore by lazy { GuideStore(guide, liveScope, hidden) }
    fun cardProgrammes(source: String, itemId: String): Flow<List<Slot>> = guideStore.cardProgrammes(source, itemId)
    fun nowAiring(source: String, now: Long, query: String = ""): Flow<List<AiringProgram>> = nowAiring(source, flowOf(now), query)
    fun nowAiring(source: String, now: Flow<Long>, query: String = ""): Flow<List<AiringProgram>> = guideStore.nowAiring(source, now, query)
    fun searchPrograms(source: String, query: String, from: Long, now: Long): Flow<List<ProgramHit>> =
        searchPrograms(source, query, flowOf(from to now))
    fun searchPrograms(source: String, query: String, times: Flow<Pair<Long, Long>>): Flow<List<ProgramHit>> =
        guideStore.searchPrograms(source, query, times)

    val subtitles = SubtitleService(preferences, SubtitleCache(java.io.File(context.cacheDir, "subtitles")))

    suspend fun production(item: Item): Item = if (item.kind == "EPISODE") library.item(item.parent) ?: item
        else library.item(item.id) ?: item

    fun trim() {
        synchronized(liveOrders) { liveOrders.clear() }
        // P5 removed versionIndex; P2's channel-number snapshots are the remaining cache.
        liveNumberSnapshots.clear()
    }

    suspend fun versions(item: Item): List<Item> {
        val all = allVersions(item)
        return if (preferences.showAdult.first()) all else all.filterNot { isAdultCategory(it.category) }
    }

    private suspend fun allVersions(item: Item): List<Item> = withContext(Dispatchers.IO) {
        val base = production(item)
        if (base.kind !in listOf("MOVIE", "SERIES")) return@withContext listOf(base)
        if (base.versionKey.isNotEmpty()) return@withContext library.versionRows(base.versionKey)
        // Legacy rows from the 7 to 8 migration have no grouping key. Avoid scanning the full
        // source on first detail/playback; the next import assigns keys in one bounded projection.
        listOf(base)
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
        val version = choices.preferredVersion(preferred, base)
        return resolveVersion(item, version)
    }

    suspend fun chooseVersion(item: Item, version: Item): Item {
        val resolved = resolveVersion(item, version)
        preferences.set("version_${versionPreferenceIdentity(item)}", "id:${version.id}")
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
                        }
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

    // Hold the same lock through networking and cache writes, so an in-flight check
    // cannot cross a backup/restore snapshot. Automatic checks skip a busy repository.
    suspend fun checkUpdate(repository: String, current: String, automatic: Boolean = false): Update? =
        withContext(Dispatchers.IO) {
            if (automatic) {
                if (!lock.tryLock()) return@withContext null
            } else lock.lock()
            try { Updates.check(repository, current, preferences, automatic) }
            finally { lock.unlock() }
        }

    // Queue the launch read with catalog commits, before a later import can
    // overtake the first composition. Downloads do not hold up this snapshot.
    suspend fun sourceSnapshot(): List<Source> = db.withTransaction { library.snapshot() }

    private val backups by lazy {
        BackupStore(db, vault, preferences, metadata, restoreTransaction,
            { source, spec -> import(source, spec, updatePreferences = false) }, { expandUnlocked(it, force = true) }) { source ->
            for (feed in guide.feeds(source.id)) syncGuide(source, feed)
        }
    }

    suspend fun createBackup(password: CharArray): ByteArray = withContext(Dispatchers.IO) {
        lock.withLock { BackupCodec.encrypt(backups.snapshot(), password) }
    }

    suspend fun previewBackup(bytes: ByteArray, password: CharArray): BackupPreview = withContext(Dispatchers.IO) {
        lock.withLock { BackupPreview(BackupCodec.decrypt(bytes, password)) }
    }

    suspend fun restoreBackup(preview: BackupPreview): BackupRestoreResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val result = backups.restore(preview.data)
            // Scheduling is follow-up work; its failure must not report a committed restore as failed.
            try { schedule(preferences.text("hours", "12").first().toLongOrNull()?.coerceIn(1L, 168L) ?: 12L) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* The saved interval will be scheduled on the next launch. */ }
            result
        }
    }

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

    private suspend fun syncGuide(source: Source, feed: Feed) {
        val key = "feed_${feed.id}"
        val job = currentCoroutineContext()
        Transport.download(vault.decrypt(feed.encryptedAddress), source.allowHttp, context.cacheDir,
            preferences.fetchState(key), limit = 256L * 1024 * 1024, checkActive = { job.ensureActive() }).use { downloaded ->
            job.ensureActive()
            val now = System.currentTimeMillis()
            val changed = downloaded.importIfChanged {
                db.withTransaction {
                    downloaded.read { input, _ ->
                        val buffered = input.buffered()
                        buffered.mark(2)
                        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
                        buffered.reset()
                        val xml = if (gzip) bounded(java.util.zip.GZIPInputStream(buffered), 256L * 1024 * 1024)
                            else buffered
                        populateGuide(db, feed, xml, now) { job.ensureActive() }
                    }
                }
            }
            if (!changed) {
                // Window maintenance is independent of importing an unchanged feed.
                val (from, until) = guideWindow(now)
                if (guide.hasOutsideWindow(feed.id, from, until)) guide.pruneWindow(feed.id, from, until)
            }
            preferences.setFetchState(key, downloaded.state)
        }
    }

    private suspend fun import(source: Source, spec: SourceSpec, updatePreferences: Boolean = true) {
        val token = maxOf(System.currentTimeMillis(), source.refreshed + 1)
        val key = "source_${source.id}"
        val previous = if (source.refreshed > 0) preferences.fetchState(key) else FetchState()
        val xtream = if (spec.user.isNotEmpty()) Xtream(spec) else null
        var downloaded: Download? = null
        var snapshot: Xtream.Snapshot? = null
        val job = currentCoroutineContext()
        try {
            if (xtream != null) snapshot = xtream.prepare(context.cacheDir, previous) { job.ensureActive() }
            else if (!spec.address.startsWith("content://"))
                downloaded = Transport.download(spec.address, spec.allowHttp, context.cacheDir, previous,
                    checkActive = { job.ensureActive() })
            job.ensureActive()
            val body = downloaded
            val catalog = snapshot
            val state = catalog?.state ?: body?.state
            val changed = if (body != null) body.importIfChanged {
                importChanged(source, spec, token, xtream, catalog, body)
            } else if (catalog?.unchanged != true) {
                importChanged(source, spec, token, xtream, catalog, body)
                true
            } else false
            if (!changed) {
                library.save(source.copy(refreshed = token))
            }
            val providerLimit = catalog?.account?.optJSONObject("user_info")?.optInt("max_connections", 0) ?: 0
            // Restore publishes all settings in one edit, after every source has imported.
            if (updatePreferences) {
                if (providerLimit > 0) preferences.set("provider_${source.id}", providerLimit.toString())
                if (state != null) preferences.setFetchState(key, state)
            }
        } finally {
            snapshot?.close()
            downloaded?.close()
        }
    }

    private suspend fun importChanged(source: Source, spec: SourceSpec, token: Long,
        xtream: Xtream?, snapshot: Xtream.Snapshot?, downloaded: Download?) {
        val staged = CatalogStage(context.cacheDir, source.id, token)
        val job = currentCoroutineContext()
        try {
            staged.write { append ->
                val grouper = SeriesGrouper()
                fun accept(row: Listing) {
                    job.ensureActive()
                    if (spec.user.isNotEmpty()) append(item(source.id, token, row))
                    else grouper.accept(row) { append(item(source.id, token, it)) }
                }
                when {
                    spec.user.isNotEmpty() -> checkNotNull(xtream).read(snapshot, ::accept)
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
                        checkNotNull(downloaded).read { input, finalUrl ->
                            Playlist.read(
                                input.bufferedReader(),
                                URI(finalUrl),
                                spec.allowHttp,
                                ::accept,
                            )
                        }
                }
            }
            db.withTransaction {
                library.save(source)
                staged.read { batch ->
                    job.ensureActive()
                    library.writeBatch(batch.map { it.copy(versionKey = it.id, displayTitle = it.title, displayYear = it.year) })
                }
                if (spec.user.isEmpty()) library.prunePlaylist(source.id, token)
                else library.prune(source.id, token)
                library.orphans(source.id)
                for (kind in listOf("MOVIE", "SERIES")) {
                    val rows = library.versionCandidates(source.id, kind).map(VersionCandidate::asItem)
                    // Resolve across the complete source, not each 128-row staging batch: matching
                    // translated titles and a missing TMDB ID may occur in distant batches.
                    library.assignVersions(rows.versionAssignments())
                    rows.versionGroups().filter { it.size > 1 }.forEach { versions ->
                        job.ensureActive()
                        val marks = versions.mapNotNull { library.mark(it.id) }
                        val newest = marks.maxByOrNull { it.updated }
                        if (newest != null) {
                            val shared = newest.copy(favorite = marks.any { it.favorite }, listed = marks.any { it.listed })
                            versions.forEach { library.save(shared.copy(id = it.id)) }
                        }
                    }
                }
                job.ensureActive()
                library.save(source.copy(refreshed = token, count = library.count(source.id)))
            }
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
            lock.withLock { expandUnlocked(series) }
        }

    // Restore already owns the repository lock and must refresh episodes even with a warm cache.
    private suspend fun expandUnlocked(series: Item, force: Boolean = false): List<Item> {
        val source = library.source(series.source) ?: return emptyList()
        val spec = SourceSpec.decode(vault.decrypt(source.endpoint))
        if (spec.user.isEmpty()) return library.episodeSnapshot(series.id)
        suspend fun fetch(): List<Item> {
            val token = System.currentTimeMillis()
            val listings = Xtream(spec)
                .episodes(vault.decrypt(series.encryptedUrl), series.title)
                .distinctBy { it.url }
            val entries = listings.map { item(source.id, token, it).copy(parent = series.id) }
            db.withTransaction {
                library.save(entries)
                val siblings = if (entries.isEmpty()) emptyList() else allVersions(series)
                    .filter { it.id != series.id }.flatMap { library.episodeSnapshot(it.id) }
                // Android 9 SQLite allows 999 binds: normally one IN query, bounded for
                // very long series. Read current and sibling marks together, never per episode.
                val marks = (entries + siblings).map { it.id }.distinct().chunked(900)
                    .flatMap { library.marksByIds(it) }.associateBy { it.id }
                val shared = siblings.groupBy { it.seriesEpisode().let { n -> n.season to n.episode } }
                    .mapValues { (_, episodes) -> episodes.mapNotNull { marks[it.id] }.maxByOrNull { it.updated } }
                listings.zip(entries).forEach { (listing, episode) ->
                    val existing = marks[episode.id]
                    var mark = existing ?: Mark(episode.id)
                    var changed = false
                    if (mark.duration <= 0 && listing.durationMs > 0)
                        mark = mark.copy(duration = listing.durationMs).also { changed = true }
                    if (mark.updated == 0L) {
                        val number = episode.seriesEpisode()
                        shared[number.season to number.episode]?.let {
                            mark = it.copy(id = episode.id)
                            changed = true
                        }
                    }
                    if (changed) library.save(mark)
                }
                library.pruneEpisodes(series.id, token)
            }
            return entries
        }
        return if (force) fetch() else seriesExpansionCache.get(series.id,
            cached = { library.episodeSnapshot(series.id) }, fetch = { fetch() })
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

    suspend fun schedule(hours: Long, checkNow: Boolean = false): Unit = withContext(Dispatchers.IO) {
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
    private fun postponeIfBusy(forceAll: Boolean = false): Result? {
        if (!RefreshGate.busy) return null
        val now = System.currentTimeMillis()
        val deadline = inputData.getLong("deferUntil", 0).takeIf { it > 0 }
            ?: (now + TimeUnit.MINUTES.toMillis(30))
        if (RefreshGate.defer(now, deadline)) {
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "deferred-refresh", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(minOf(TimeUnit.MINUTES.toMillis(5), deadline - now), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("deferUntil" to deadline,
                        "staleHours" to if (forceAll) 0L else inputData.getLong("staleHours", 0)))
                    .build())
        }
        // At 30 minutes skip this occurrence, rather than starting heavy work during playback.
        return Result.success()
    }

    private class RefreshBusy : kotlinx.coroutines.CancellationException()

    override suspend fun doWork(): Result = coroutineScope {
        val refresh = async { refreshSources() }
        val monitor = launch {
            while (refresh.isActive) {
                delay(500)
                if (RefreshGate.busy) {
                    refresh.cancel(RefreshBusy())
                    break
                }
            }
        }
        try {
            refresh.await()
        } catch (_: RefreshBusy) {
            // Cancellation may follow a catalog commit but precede its guide; resume both.
            postponeIfBusy(forceAll = true) ?: Result.retry()
        } finally {
            monitor.cancel()
        }
    }

    private suspend fun refreshSources(): Result {
        postponeIfBusy()?.let { return it }
        val repo = Repository.get(applicationContext)
        var failed = false
        val prefs = Preferences(applicationContext)
        for (source in repo.library.snapshot()) {
            postponeIfBusy()?.let { return it }
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
