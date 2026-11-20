package dev.lumenara.tv

import androidx.lifecycle.viewModelScope
import dev.lumenara.catalog.searchable
import dev.lumenara.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class Filter(
    val source: String = "",
    val kind: String = "",
    val category: String = "",
    val query: String = "",
    val shelf: String = "",
    val limit: Int = 80,
    val year: String = "",
    val genre: String = "",
    val language: String = "",
    val rating: Double = 0.0,
)

internal data class HubRows(val source: String, val rows: List<Item>)

@OptIn(ExperimentalCoroutinesApi::class)
@dagger.hilt.android.lifecycle.HiltViewModel
internal class LibraryModel
@javax.inject.Inject
constructor(private val repository: dagger.Lazy<Repository>, val prefs: Preferences) : androidx.lifecycle.ViewModel() {
    // Recovery must finish before exposing repository state, but never block Hilt/UI creation.
    val repo: Repository get() = repository.get()
    private val firstDraw = FirstDrawGate()
    val firstDrawn = firstDraw.drawn
    fun onFirstDraw() { firstDraw.onDraw() }
    suspend fun awaitFirstDraw() = firstDraw.await()
    // Cheap visible details may begin after the populated frame; totals start independently.
    val hubDetailsReady = firstDraw.detailsReady
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    // Keep non-visible publishing, scheduling, update checks and native warm-up out of
    // the startup/remote-input window.
    val backgroundReady = flow {
        awaitFirstDraw()
        delay(20_000)
        emit(true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val startInSetup: StateFlow<Boolean?> = MutableStateFlow<Boolean?>(null).also { state ->
        viewModelScope.launch(Dispatchers.IO) {
            Perf.mark("startup_sources")
            state.value = repo.sourceSnapshot().isEmpty()
            Perf.since("startup_sources", "startup_sources_ready")
        }
    }.asStateFlow()
    val sources = flow { emitAll(withContext(Dispatchers.IO) { repo }.library.sources()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val hubScope = CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)
    private val hubSource = MutableStateFlow("")
    fun selectHubSource(source: String) { hubSource.value = source }
    // Start the bounded history reads with the launch snapshot. Starting collectAsState
    // inside Home queues their subscriptions behind its expensive first compose/measure.
    // The source travels with the result so changing scope never flashes the old shelf.
    val hubRecent = hubSource.flatMapLatest { source -> flow {
        Perf.mark("startup_hub_recent")
        emitAll(repo.recentLive(source).onEach {
            Perf.since("startup_hub_recent", "startup_hub_recent_ready")
        }.map { HubRows(source, it) })
    } }.stateIn(hubScope, SharingStarted.Eagerly, null)
    val hubResume = hubSource.flatMapLatest { source -> flow {
        Perf.mark("startup_hub_resume")
        emitAll(repo.continueWatching(source).onEach {
            Perf.since("startup_hub_resume", "startup_hub_resume_ready")
        }.map { HubRows(source, it) })
    } }.stateIn(hubScope, SharingStarted.Eagerly, null)
    val hubTotals = hubSource.flatMapLatest { source -> flow {
        Perf.mark("startup_hub_counts")
        emitAll(repo.hubTotals(source).map { rows ->
            Perf.since("startup_hub_counts", "startup_hub_counts_ready")
            source to rows.associate { it.kind to it.count }
        })
    } }.stateIn(hubScope, SharingStarted.Eagerly, null)
    val filter = MutableStateFlow(Filter())
    val results =
        filter
            .flatMapLatest { f ->
                if (f.query.isNotEmpty()) delay(200)
                repo.browse(
                    f.source,
                    f.kind,
                    f.category,
                    searchable(f.query),
                    f.shelf,
                    f.limit,
                    f.year,
                    f.genre,
                    f.language,
                    f.rating,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow("")
    val importProgress = ImportProgress()
    private var activeJob: Job? = null

    fun cancelJob() { activeJob?.cancel() }
    val update = MutableStateFlow<Update?>(null)
    val watchNextPlayback = MutableStateFlow<WatchNextPlayback?>(null)
    val remoteSearch = MutableStateFlow(0)
    val searchIntent = MutableStateFlow<Pair<Int, dev.lumenara.catalog.SearchRequest>?>(null)
    val searchPrefill = MutableStateFlow<Pair<Int, String>?>(null)
    private var searchIntentId = 0

    fun receiveSearchIntent(request: dev.lumenara.catalog.SearchRequest) {
        watchNextPlayback.value = null
        remoteSearch.value = 0
        searchIntent.value = (++searchIntentId) to request
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // DDL opens immediately; catalog upgrades yield the first 15 seconds to the UI.
            backgroundReady.first { it }
            repo.startVersionBackfill()
        }
        viewModelScope.launch(Dispatchers.IO) {
            backgroundReady.first { it }
            Perf.mark("startup_preferences")
            val hours = prefs.text("hours", "12").first().toLong()
            val repository = prefs.text("github").first()
            Perf.since("startup_preferences", "startup_preferences_ready")
            Perf.mark("startup_schedule")
            repo.schedule(hours, checkNow = true)
            Perf.since("startup_schedule", "startup_refresh_scheduled")
            if (repository.isNotEmpty()) {
                Perf.mark("startup_update")
                try {
                    checkUpdate(repository, automatic = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
                finally { Perf.since("startup_update", "startup_update_complete") }
            }
        }
    }

    suspend fun checkUpdate(repository: String, automatic: Boolean = false) {
        update.value = if (repository.isEmpty()) null else
            repo.checkUpdate(repository, BuildConfig.VERSION_NAME, automatic)
    }

    fun diagnostic(code: String) {
        viewModelScope.launch {
            if (prefs.text("diagnostics", "Kapalı").first() == "Açık") {
                val rows =
                    (prefs.text("events").first().lines().filter { it.isNotBlank() } +
                            "${java.time.Instant.now()} · $code")
                        .takeLast(60)
                prefs.set("events", rows.joinToString("\n"))
            }
        }
    }

    fun job(success: String, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        notice.value = ""
        importProgress.reset()
        activeJob = viewModelScope.launch {
            try {
                action()
                notice.value = success
            } catch (e: CancellationException) {
                notice.value = "İşlem iptal edildi."
                throw e
            } catch (e: SourceFailure) {
                notice.value = e.message.orEmpty()
            } catch (_: java.net.SocketTimeoutException) {
                notice.value = "Bağlantı zaman aşımına uğradı. Yeniden deneyin."
            } catch (_: Exception) {
                notice.value =
                    "İşlem tamamlanamadı. Adresi, bağlantıyı ve kaynak izinlerini kontrol edin."
            } finally {
                busy.value = false
            }
        }
    }

    fun mark(item: Item, favorite: Boolean) {
        viewModelScope.launch {
            var removed = false
            repo.updateMark(item.id) {
                removed = !favorite && it.listed
                if (favorite) it.copy(favorite = !it.favorite) else it.copy(listed = !it.listed)
            }
            if (removed) WatchNext.removedFromList(item.id)
        }
    }

    fun watched(item: Item, value: Boolean) {
        viewModelScope.launch {
            repo.updateMark(item.id) {
                // Unknown durations use a positive sentinel until real playback supplies a duration.
                val total = it.duration.takeIf { d -> d > 0 } ?: 1L
                it.copy(position = if (value) total else 0, duration = if (value) total else it.duration,
                    updated = if (value) System.currentTimeMillis() else 0)
            }
        }
    }

    fun started(item: Item) {
        if (item.kind != "LIVE") return
        viewModelScope.launch { prefs.recordLive(item.id) }
    }

    fun progress(item: Item, time: Long, duration: Long) {
        if (item.kind in listOf("LIVE", "REPLAY") || time < 0) return
        viewModelScope.launch {
            repo.updateMark(item.id) {
                it.copy(
                    position = time,
                    duration = duration.coerceAtLeast(0),
                    updated = System.currentTimeMillis(),
                )
            }
        }
    }
}
