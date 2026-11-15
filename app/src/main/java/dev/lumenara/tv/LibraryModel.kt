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

@OptIn(ExperimentalCoroutinesApi::class)
@dagger.hilt.android.lifecycle.HiltViewModel
internal class LibraryModel
@javax.inject.Inject
constructor(val repo: Repository, val prefs: Preferences) : androidx.lifecycle.ViewModel() {
    val startInSetup: StateFlow<Boolean?> = MutableStateFlow<Boolean?>(null).also { state ->
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.value = repo.sourceSnapshot().isEmpty()
        }
    }.asStateFlow()
    val sources = repo.library.sources().stateIn(viewModelScope, SharingStarted.Eagerly, null)
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
        viewModelScope.launch {
            repo.schedule(prefs.text("hours", "12").first().toLong(), checkNow = true)
            val repository = prefs.text("github").first()
            if (repository.isNotEmpty()) {
                try {
                    checkUpdate(repository)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun checkUpdate(repository: String) {
        update.value = if (repository.isEmpty()) null else Updates.check(repository, BuildConfig.VERSION_NAME)
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
        viewModelScope.launch {
            try {
                action()
                notice.value = success
            } catch (e: CancellationException) {
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
