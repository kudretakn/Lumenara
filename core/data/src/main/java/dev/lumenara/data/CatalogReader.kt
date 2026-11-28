package dev.lumenara.data

import androidx.room.InvalidationTracker
import dev.lumenara.catalog.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Room projections/visibility and idle search-index maintenance; no import or restore. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class CatalogReader(
    private val db: LibraryDb,
    private val preferences: Preferences,
    private val catalogScope: CoroutineScope,
    private val liveScope: CoroutineScope,
    private val liveOrders: MutableMap<String, StateFlow<List<LiveChannelId>>>,
) {
    private val library = db.library()
    private val guide = db.guide()
    private val catalog = CatalogStreams(library.visibleAdultCategoryNames(),
        { kind, source, hidden -> library.categoryCounts(kind, source, hidden) },
        { source, hidden -> library.sourceCategoryCounts(source, hidden) },
        preferences.showAdult, catalogScope, StartupPerf::duration, preclassified = true)
    // Start only when a published reader is consumed, never during Repository recovery.
    val hidden: Flow<List<String>> = catalog.hidden.onStart { startSearchBackfill() }
    // Upgraded rows use their original category until stored classification is available.
    private fun Flow<List<Item>>.storedVisibility(): Flow<List<Item>> =
        combine(preferences.showAdult) { rows, show -> rows.visibleStoredRows(show) }

    fun browse(source: String, kind: String, category: String, query: String, shelf: String, limit: Int,
               year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) =
        hidden.flatMapLatest { library.browse(source, kind, category, query, shelf, limit, year, genre, language, minRating, it) }.storedVisibility()

    fun browsePage(source: String, kind: String, category: String, query: String, shelf: String,
        limit: Int, offset: Int, year: String = "", genre: String = "", language: String = "", minRating: Double = 0.0) =
        hidden.flatMapLatest { library.browsePage(source, kind, category, query, shelf, limit, offset,
            year, genre, language, minRating, it) }.storedVisibility()

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
        hidden.flatMapLatest { hidden -> library.liveCategoryCounts(source, hidden) }
            .distinctUntilChanged()
    /** A bounded visible page, with no whole-catalog GuideResolver or future schedules. */
    fun liveNow(rows: List<Item>, now: Long): Flow<Map<String, Slot>> = flow {
        if (rows.isEmpty()) emit(emptyMap()) else emitAll(
            library.liveNow(rows, now).map { current ->
                current.groupBy { it.itemId }.mapValues { (_, hits) -> hits.first().slot }
            })
    }.flowOn(Dispatchers.Default)
    fun liveByIds(source: String, ids: List<String>) =
        hidden.flatMapLatest { library.liveByIds(source, ids, it) }.storedVisibility()
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
        val show = preferences.showAdult.first()
        val ready = library.catalogReady(source).first()
        val excluded = if (show || ready) emptyList() else library.visibleAdultCategoryNames().first()
        suspend fun at(id: String, value: Int) = if (!ready) library.providerChannel(id, value - 1, excluded)
            else if (show) db.derived().channel(id, value) else db.derived().safeChannel(id, value)
        val selected = source.ifEmpty {
            library.snapshot().firstOrNull { at(it.id, 1) != null }?.id ?: return null
        }
        return at(selected, number)
    }

    fun livePage(source: String, category: String, query: String, shelf: String, limit: Int, offset: Int) =
        hidden.flatMapLatest { library.livePage(source, category, query, shelf, limit, offset, it) }.storedVisibility()
    fun livePageAfter(source: String, category: String, query: String, shelf: String, limit: Int,
        after: LiveCursor?, backwards: Boolean = false) =
        hidden.flatMapLatest { library.livePageAfter(source, category, query, shelf, limit, after, backwards, it) }.storedVisibility()

    fun liveNeighbors(source: String, category: String, query: String, shelf: String, limit: Int,
        id: String, backwards: Boolean = false) =
        hidden.flatMapLatest { library.liveNeighbors(source, category, query, shelf, limit, id, backwards, it) }.storedVisibility()

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
                emit(LiveWindow(current.visibleStoredRows(preferences.showAdult.first()), liveWindowEpoch(shelf), visible))
                initial = false
            }
        }
    }

    private val liveOrderEpoch = java.util.concurrent.atomic.AtomicLong()
    private val liveFavoriteEpoch = java.util.concurrent.atomic.AtomicLong()
    private fun liveWindowEpoch(shelf: String): Long =
        if (shelf == "favorite") liveFavoriteEpoch.get() else liveOrderEpoch.get()
    private val liveInvalidation = object : InvalidationTracker.Observer("items", "marks", "catalog_derived_state") {
        override fun onInvalidated(tables: Set<String>) {
            if ("items" in tables || "catalog_derived_state" in tables) liveOrderEpoch.incrementAndGet()
            liveFavoriteEpoch.incrementAndGet()
        }
    }
    init { db.invalidationTracker.addObserver(liveInvalidation) }

    private var searchBackfillJob: Job? = null
    private var searchBackfillFinished = false
    @Synchronized private fun startSearchBackfill() {
        if (searchBackfillFinished || searchBackfillJob?.isActive == true) return
        searchBackfillJob = catalogScope.launch(Dispatchers.IO) {
            try {
                val backfill = Wp19SearchBackfill(db)
                Wp07MaintenanceRunner(backgroundWork, { false }, android.os.SystemClock::elapsedRealtime) { limit ->
                    backfill.runBatch(limit.coerceAtMost(16))
                }.run()
                val series = Wp22SeriesBackfill(db)
                Wp07MaintenanceRunner(backgroundWork, { false }, android.os.SystemClock::elapsedRealtime) { limit ->
                    series.runBatch(limit.coerceAtMost(100))
                }.run()
                synchronized(this@CatalogReader) { searchBackfillFinished = true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The old query remains available; a new process resumes the committed cursor.
                StartupPerf.duration("wp19_search_backfill_failed", 0)
            }
        }
    }

    /** Only requested IDs; pending upgrades use the same provider order as live pages. */
    fun liveChannelNumbers(source: String, ids: List<String>): Flow<Map<String, Int>> =
        combine(preferences.showAdult, library.catalogReady(source).distinctUntilChanged()) { show, ready -> show to ready }
            .flatMapLatest { (show, ready) -> flow {
                val excluded = if (show || ready) emptyList() else library.visibleAdultCategoryNames().first()
                val rows = ids.distinct().chunked(250).flatMap {
                    if (ready) db.derived().numbered(source, it) else library.providerChannelNumbers(source, it, excluded)
                }
                emit(rows.mapNotNull { row ->
                    val number = if (show) row.channelNumber else row.safeChannelNumber
                    number.takeIf { it > 0 }?.let { row.id to it }
                }.toMap())
            } }.flowOn(Dispatchers.IO)

    fun hubTotals(source: String): Flow<List<HubTotal>> = preferences.showAdult.flatMapLatest { show ->
        library.withCatalog(source) { ready ->
            if (ready) db.derived().totals(source, show)
            else if (show) library.fallbackHubTotals(source, emptyList())
            else library.visibleAdultCategoryNames().flatMapLatest { library.fallbackHubTotals(source, it) }
        }
    }.distinctUntilChanged()

    fun liveCount(source: String, category: String = "", query: String = "", shelf: String = "") =
        hidden.flatMapLatest { hidden -> library.liveCount(source, category, query, shelf, hidden) }
    suspend fun liveIndex(source: String, id: String, category: String = "", query: String = "", shelf: String = "") =
        library.liveIndex(source, id, category, query, shelf, hidden.first())
    fun episodes(id: String) = hidden.flatMapLatest { library.episodes(id, it) }
    suspend fun episodeSnapshot(id: String) = library.episodeSnapshot(id, hidden.first())
    // Visibility for history needs only marked cards, their parents and those parents' episodes.
    // Do not make the Hub wait for names/counts over the complete provider catalog.
    private val historyHidden = combine(preferences.showAdult, library.continueWatchingCategories()
        .traceFirstResult("startup_hub_history_visibility_ready")) { show, names ->
        if (show) emptyList() else names.filter(::isAdultCategory)
    }.distinctUntilChanged()
    fun continueWatching(source: String) =
        historyHidden.flatMapLatest { library.continueWatching(source, it)
            .traceFirstResult("startup_hub_resume_query_ready") }.flowOn(Dispatchers.Default)
    fun recentLive(source: String) = preferences.recentLive.flatMapLatest { saved ->
        val ids = saved.distinct().take(20)
        if (ids.isEmpty()) return@flatMapLatest flowOf(emptyList<Item>())
        // Twenty primary-key probes need no full-catalog category-name query. Apply the same
        // visibility rule to these bounded rows, preserving DataStore's recency order.
        combine(library.liveByIds(source, ids).traceFirstResult("startup_hub_recent_query_ready"),
            preferences.showAdult) { rows, showAdult ->
            val byId = rows.associateBy { it.id }
            visibleRecentIds(ids, rows.associate { it.id to it.category }, showAdult).mapNotNull { byId[it] }
        }
    }.flowOn(Dispatchers.Default)
    private val guideStore by lazy { GuideStore(guide, liveScope, hidden, traceWork = StartupPerf::duration) }
    fun cardProgrammes(source: String, itemId: String): Flow<List<Slot>> = guideStore.cardProgrammes(source, itemId)
    fun nowAiring(source: String, now: Long, query: String = ""): Flow<List<AiringProgram>> = nowAiring(source, flowOf(now), query)
    fun nowAiring(source: String, now: Flow<Long>, query: String = ""): Flow<List<AiringProgram>> = guideStore.nowAiring(source, now, query)
    fun searchPrograms(source: String, query: String, from: Long, now: Long): Flow<List<ProgramHit>> =
        searchPrograms(source, query, flowOf(from to now))
    fun searchPrograms(source: String, query: String, times: Flow<Pair<Long, Long>>): Flow<List<ProgramHit>> =
        guideStore.searchPrograms(source, query, times)

    suspend fun production(item: Item): Item = if (item.kind == "EPISODE") library.item(item.parent) ?: item
        else library.item(item.id) ?: item

    suspend fun versions(item: Item): List<Item> {
        val all = allVersions(item)
        return if (preferences.showAdult.first()) all else all.filterNot { isAdultCategory(it.category) }
    }

    suspend fun allVersions(item: Item): List<Item> = withContext(Dispatchers.IO) {
        val base = production(item)
        if (base.kind !in listOf("MOVIE", "SERIES")) return@withContext listOf(base)
        if (db.versionBackfill().pending(base.id)) return@withContext listOf(base)
        if (base.versionKey.isNotEmpty()) return@withContext library.versionRows(base.versionKey)
        // Legacy rows from the 7 to 8 migration have no grouping key. Avoid scanning the full
        // source on first detail/playback; the next import assigns keys in one bounded projection.
        listOf(base)
    }

}
