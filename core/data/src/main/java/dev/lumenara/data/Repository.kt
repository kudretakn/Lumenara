package dev.lumenara.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import dev.lumenara.catalog.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancel
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
    private val db = LibraryDb.build(context)
    val library = db.library()
    val guide = db.guide()
    private val vault = Vault(context)
    private val metadata = Metadata(context, vault)
    private val preferences = Preferences(context)
    private val restoreTransaction = wp16RestoreTransaction(db, vault, preferences, metadata)
    private val spool = EncryptedSpool(vault)
    // Called on IO by the process coordinator, before publishing this instance.
    private suspend fun recoverBeforePublication() {
        try {
            EncryptedSpool.cleanup(context.cacheDir)
            StartupPerf.measure("startup_room_open") { db.openHelper.writableDatabase }
            StartupPerf.measure("startup_room_recovery") { restoreTransaction.recover() }
        } catch (error: Throwable) {
            catalogScope.cancel()
            liveScope.cancel()
            db.close()
            if (error is Exception) throw wp16Failure(error, Wp16Stage.BACKUP)
            throw error
        }
    }
    private val catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Retain the existing bounded order-cache ownership and trim contract.
    private val liveOrders = mutableMapOf<String, StateFlow<List<LiveChannelId>>>()
    private val reader = CatalogReader(db, preferences, catalogScope, liveScope, liveOrders)
    val hidden: Flow<List<String>> = reader.hidden

    fun browse(source: String, kind: String, category: String, query: String, shelf: String, limit: Int,
               year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) = reader.browse(source, kind, category, query, shelf, limit, year, genre, language, minRating)

    fun browsePage(source: String, kind: String, category: String, query: String, shelf: String,
        limit: Int, offset: Int, year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) = reader.browsePage(source, kind, category, query, shelf, limit, offset, year, genre, language, minRating)

    fun browseCount(source: String, kind: String, category: String, query: String, shelf: String,
        year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) = reader.browseCount(source, kind, category, query, shelf, year, genre, language, minRating)

    suspend fun browseIndex(source: String, kind: String, category: String, query: String, shelf: String,
        id: String, year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) = reader.browseIndex(source, kind, category, query, shelf, id, year, genre, language, minRating)

    fun browseKinds(source: String, kind: String, category: String, query: String, shelf: String,
        year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) = reader.browseKinds(source, kind, category, query, shelf, year, genre, language, minRating)

    fun shelfCount(source: String, kind: String, shelf: String) = reader.shelfCount(source, kind, shelf)

    fun categoryCounts(kind: String, source: String) = reader.categoryCounts(kind, source)

    fun categories(kind: String, source: String) = reader.categories(kind, source)

    fun liveCategoryCounts(source: String) = reader.liveCategoryCounts(source)

    fun liveNow(rows: List<Item>, now: Long): Flow<Map<String, Slot>> = reader.liveNow(rows, now)

    fun liveByIds(source: String, ids: List<String>) = reader.liveByIds(source, ids)

    fun liveChannelOrder(source: String): StateFlow<List<LiveChannelId>> = reader.liveChannelOrder(source)

    suspend fun liveChannel(number: Int, source: String): Item? = reader.liveChannel(number, source)

    fun livePage(source: String, category: String, query: String, shelf: String, limit: Int, offset: Int) = reader.livePage(source, category, query, shelf, limit, offset)

    fun livePageAfter(source: String, category: String, query: String, shelf: String, limit: Int,
        after: LiveCursor?, backwards: Boolean = false) = reader.livePageAfter(source, category, query, shelf, limit, after, backwards)

    fun liveNeighbors(source: String, category: String, query: String, shelf: String, limit: Int,
        id: String, backwards: Boolean = false) = reader.liveNeighbors(source, category, query, shelf, limit, id, backwards)

    fun liveWindow(source: String, category: String, query: String, shelf: String, limit: Int,
        offset: Int, after: LiveWindowCursor?): Flow<LiveWindow> = reader.liveWindow(source, category, query, shelf, limit, offset, after)

    fun liveChannelNumbers(source: String, ids: List<String>): Flow<Map<String, Int>> = reader.liveChannelNumbers(source, ids)

    fun hubTotals(source: String): Flow<List<HubTotal>> = reader.hubTotals(source)

    fun liveCount(source: String, category: String = "", query: String = "", shelf: String = "") = reader.liveCount(source, category, query, shelf)

    suspend fun liveIndex(source: String, id: String, category: String = "", query: String = "", shelf: String = "") = reader.liveIndex(source, id, category, query, shelf)

    fun episodes(id: String) = reader.episodes(id)

    suspend fun episodeSnapshot(id: String) = reader.episodeSnapshot(id)

    fun continueWatching(source: String) = reader.continueWatching(source)

    fun recentLive(source: String) = reader.recentLive(source)

    fun cardProgrammes(source: String, itemId: String): Flow<List<Slot>> = reader.cardProgrammes(source, itemId)

    fun nowAiring(source: String, now: Long, query: String = ""): Flow<List<AiringProgram>> = reader.nowAiring(source, now, query)

    fun nowAiring(source: String, now: Flow<Long>, query: String = ""): Flow<List<AiringProgram>> = reader.nowAiring(source, now, query)

    fun searchPrograms(source: String, query: String, from: Long, now: Long): Flow<List<ProgramHit>> = reader.searchPrograms(source, query, from, now)

    fun searchPrograms(source: String, query: String, times: Flow<Pair<Long, Long>>): Flow<List<ProgramHit>> = reader.searchPrograms(source, query, times)

    val subtitles = SubtitleService(preferences, SubtitleCache(java.io.File(context.cacheDir, "subtitles")))

    suspend fun production(item: Item): Item = reader.production(item)

    fun trim() {
        synchronized(liveOrders) { liveOrders.clear() }

    }

    suspend fun versions(item: Item): List<Item> = reader.versions(item)

    private suspend fun allVersions(item: Item): List<Item> = reader.allVersions(item)

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
                    if (query.tmdb.toLongOrNull()?.let { it > 0 } == true) {
                        lock.withLock { db.withTransaction {
                            val current = library.item(base.id)
                            if (current != null) {
                                val matched = current.withTmdbMatch(TmdbMatch(query.tmdb, query.year), authoritative = true)
                                if (matched.search != current.search || matched.year != current.year)
                                    library.assignTmdb(listOf(TmdbAssignment(current.id, matched.search, matched.year)))
                            }
                        } }

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
        metadata.details(withContext(Dispatchers.IO) { library.item(item.id) ?: item }).also {
            // Store the looked-at item's identity for the next refresh; never regroup a source here.
            withContext(Dispatchers.IO) {
                lock.withLock { db.withTransaction {
                    val current = library.item(item.id)
                    if (current != null) {
                        val matched = current.withTmdbMatch(metadata.cachedMatch(current))
                        if (matched.search != current.search || matched.year != current.year)
                            library.assignTmdb(listOf(TmdbAssignment(current.id, matched.search, matched.year)))
                    }
                } }
            }
            if (it.collection.isNotBlank())
                db.withTransaction {
                    if (library.exists(item.id)) library.collection(Member(item.id, it.collection))
                }
        }

    suspend fun configureTmdb(token: String) =
        withContext(Dispatchers.IO) { metadata.configure(token) }

    fun tmdbConfigured() = metadata.userConfigured()

    fun tmdbBuiltIn() = metadata.hasBuiltInToken

    private val lock = backgroundWork.mutex
    private val imports = SourceImports(lock)
    private var versionBackfillJob: kotlinx.coroutines.Job? = null

    /** Durable upgrade cursors resume only after content draws and remote input is idle. */
    fun startVersionBackfill() {
        synchronized(this) {
            if (versionBackfillJob?.isActive == true) return
            versionBackfillJob = catalogScope.launch(Dispatchers.IO) {
                try {
                    val backfill = VersionBackfill(db)
                    val derived = CatalogDerivedBackfill(db)
                    var started = false
                    var versionsPending = true
                    Wp07MaintenanceRunner(backgroundWork, { imports.userWaiting },
                        android.os.SystemClock::elapsedRealtime) { limit ->
                        if (!started) {
                            started = true
                            StartupPerf.duration("startup_catalog_backfill_started", 0)
                        }
                        // Never start two transactions under one gate check.
                        if (versionsPending) {
                            versionsPending = backfill.runBatch(limit, timeBudgetMs = 20)
                            true
                        } else {
                            val source = db.derived().pendingSource()
                            if (source == null) false else { derived.runBatch(source, limit); true }
                        }
                    }.run()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // The committed cursor survives. A later foreground launch retries it.
                    StartupPerf.duration("version_backfill_failed", 0)
                    android.util.Log.w("LumenaraRepository", wp16Diagnostic(error, Wp16Stage.BACKFILL))
                }
            }
        }
    }

    // Updater state is atomically edited in DataStore and excluded from backup/restore.
    // Its network requests must not delay source imports.
    suspend fun checkUpdate(repository: String, current: String, automatic: Boolean = false): Update? =
        withContext(Dispatchers.IO) {
            if (automatic && (lock.isLocked || imports.userWaiting)) return@withContext null
            Updates.check(repository, current, preferences, automatic)
        }

    // Queue the launch read with catalog commits, before a later import can
    // overtake the first composition. Downloads do not hold up this snapshot.
    suspend fun sourceSnapshot(): List<Source> = db.withTransaction { library.snapshot() }

    private val userState: UserStateCarry by lazy { UserStateCarry(context, db, vault, preferences, restoreTransaction) }
    private val guideImporter: GuideImporter by lazy { GuideImporter(context, db, vault, preferences, lock, imports, spool) }
    private val catalogImporter: CatalogImporter by lazy {
        CatalogImporter(context, db, vault, metadata, preferences, restoreTransaction, userState,
            reader, subtitles, lock, spool) { source, spec, incoming -> backups.retryPending(source, spec, incoming) }
    }
    private val importCoordinator: ImportCoordinator by lazy {
        ImportCoordinator(context.cacheDir, db, vault, preferences, imports, userState, catalogImporter, spool)
    }
    private val backups: BackupService by lazy {
        BackupService(db, lock, vault, preferences, metadata, restoreTransaction,
            { source, spec, items -> catalogImporter.prepareRestoreCatalog(source, spec, items) }) { source ->
            for (feed in guide.feeds(source.id)) guideImporter.syncGuide(source, feed)
        }
    }

    suspend fun createBackup(password: CharArray): ByteArray = backups.createBackup(password)
    suspend fun previewBackup(bytes: ByteArray, password: CharArray): BackupPreview = backups.previewBackup(bytes, password)
    suspend fun restoreBackup(preview: BackupPreview): BackupRestoreResult =
        backups.restoreBackup(preview) { hours -> schedule(hours) }

    companion object {
        @Volatile private var instance: Repository? = null

        @Volatile private var coordinator: StartupCoordinator? = null

        fun startup(context: Context): StartupCoordinator = coordinator ?: synchronized(this) {
            coordinator ?: StartupCoordinator {
                Repository(context.applicationContext).also {
                    it.recoverBeforePublication()
                    instance = it
                }
            }.also { coordinator = it }
        }

        /** Memory callbacks must only peek: no lock, allocation, Room or recovery. */
        fun readyInstance(): Repository? = instance

        suspend fun awaitReady(context: Context): Repository = startup(context).awaitReady()

        // Compatibility for existing non-suspending callers. UI startup uses awaitReady.
        fun get(context: Context): Repository = instance
            ?: kotlinx.coroutines.runBlocking { awaitReady(context) }
    }

    suspend fun add(name: String, spec: SourceSpec, progress: ImportProgress? = null) = importCoordinator.add(name, spec, progress)

    suspend fun refresh(id: String, progress: ImportProgress? = null) = importCoordinator.refresh(id, progress)

    suspend fun sourceSpec(id: String): SourceSpec = importCoordinator.sourceSpec(id)

    suspend fun editSource(id: String, name: String, spec: SourceSpec, progress: ImportProgress? = null) = importCoordinator.editSource(id, name, spec, progress)

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        wp16Operation(Wp16Stage.CARRY) { lock.withLock { userState.removeUnlocked(id) } }
    }

    suspend fun addGuide(sourceId: String, url: String) = guideImporter.addGuide(sourceId, url)

    suspend fun refreshGuides(sourceId: String, progress: ImportProgress? = null) = guideImporter.refreshGuides(sourceId, progress)

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

    suspend fun expand(series: Item): List<Item> = catalogImporter.expand(series)

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

    private val pendingRefresh by lazy {
        Wp07PendingRefresh(
            read = { preferences.text(wp07PendingRefreshKey, "false").first().toBoolean() },
            write = { preferences.set(wp07PendingRefreshKey, it.toString()) },
            enqueueUnique = { workName -> withContext(Dispatchers.IO) {
                WorkManager.getInstance(context).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RefreshWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                        .setInputData(workDataOf(wp07PendingWorker to true)).build()).result.get()
                Unit
            } })
    }
    internal suspend fun requestPendingRefresh() = pendingRefresh.request()
    internal suspend fun hasPendingRefresh() = pendingRefresh.isPending()
    internal suspend fun completePendingRefresh() = pendingRefresh.completed()
    suspend fun retryPendingRefresh() = pendingRefresh.retryIfPending()

    suspend fun schedule(hours: Long, checkNow: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            PeriodicWorkRequestBuilder<RefreshWorker>(hours, TimeUnit.HOURS)
                .setInitialDelay(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        retryPendingRefresh()
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
    private val legacyPendingWorker: Boolean get() = inputData.getLong("deferUntil", 0) > 0
    private val isPendingWorker: Boolean get() = inputData.getBoolean(wp07PendingWorker, false) || legacyPendingWorker

    private suspend fun postponeIfBusy(repo: Repository): Result? {
        if (!RefreshGate.busy) return null
        repo.requestPendingRefresh()
        // A pending worker retains its own durable WorkManager request indefinitely.
        // Periodic/startup occurrences hand off to that unique request and finish.
        return if (isPendingWorker) Result.retry() else Result.success()
    }

    private class RefreshBusy : kotlinx.coroutines.CancellationException()

    override suspend fun doWork(): Result = wp07RefreshWorkerLock.withLock {
        try {
            val repo = Repository.awaitReady(applicationContext)
            coroutineScope {
                val refresh = async { refreshSources(repo) }
                val monitor = launch {
                    while (refresh.isActive) {
                        delay(500)
                        if (RefreshGate.busy) {
                            refresh.cancel(RefreshBusy())
                            break
                        }
                    }
                }
                try { refresh.await() }
                catch (_: RefreshBusy) {
                    // A commit may precede cancellation of its guide. Retry all sources.
                    repo.requestPendingRefresh()
                    if (isPendingWorker) Result.retry() else Result.success()
                } finally { monitor.cancel() }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Includes recovery/DataStore/enqueue failures; never acknowledge lost work.
            Result.retry()
        }
    }

    private suspend fun refreshSources(repo: Repository): Result {
        // Requests persisted by the previous version also adopt the non-expiring policy.
        if (legacyPendingWorker && !repo.hasPendingRefresh()) repo.requestPendingRefresh()
        if (isPendingWorker && !repo.hasPendingRefresh()) return Result.success()
        postponeIfBusy(repo)?.let { return it }
        val forceAll = repo.hasPendingRefresh()
        var failed = false
        val prefs = Preferences(applicationContext)
        for (source in repo.library.snapshot()) {
            postponeIfBusy(repo)?.let { return it }
            val threshold = inputData.getLong("staleHours", 0)
            if (
                !forceAll && threshold > 0 &&
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
            } catch (error: Exception) {
                failed = true
                prefs.set("sync_${source.id}", wp16Diagnostic(error, Wp16Stage.IMPORT))
            }
        }
        if (!failed) repo.completePendingRefresh()
        return when {
            !failed -> Result.success()
            isPendingWorker -> Result.retry()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }
}
