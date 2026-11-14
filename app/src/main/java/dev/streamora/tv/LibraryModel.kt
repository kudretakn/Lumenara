package dev.streamora.tv

import androidx.lifecycle.viewModelScope
import dev.streamora.catalog.searchable
import dev.streamora.data.*
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
    val sources = repo.library.sources().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val filter = MutableStateFlow(Filter())
    val results =
        filter
            .flatMapLatest { f ->
                if (f.query.isNotEmpty()) delay(200)
                repo.library.browse(
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
    val remoteSearch = MutableStateFlow(0)

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
            repo.updateMark(item.id) {
                if (favorite) it.copy(favorite = !it.favorite) else it.copy(listed = !it.listed)
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
