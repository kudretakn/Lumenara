package dev.lumenara.data

import dev.lumenara.catalog.isAdultCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Repository-lifetime catalog metadata, shared by Hub, categories and content screens. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CatalogStreams(
    categories: Flow<List<String>>,
    private val counts: (String, String, List<String>) -> Flow<List<LiveCategoryCount>>,
    private val sourceCounts: (String, List<String>) -> Flow<List<KindCategoryCount>>,
    showAdult: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val traceWork: (String, Long) -> Unit = { _, _ -> },
    private val preclassified: Boolean = false,
) {
    // A null initial value means "not loaded". Emitting an empty list here would briefly expose
    // adult rows and run Hub counts twice before the real hidden list arrives.
    val hidden: Flow<List<String>> = combine(showAdult, categories) { show, names ->
        val start = System.nanoTime()
        try { if (show) emptyList() else if (preclassified) names else names.filter(::isAdultCategory) }
        finally { traceWork("startup_default_work_hidden_categories", (System.nanoTime() - start) / 1_000_000) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Lazily, null).filterNotNull()

    private val sharedCounts = mutableMapOf<Pair<String, String>, Flow<List<LiveCategoryCount>>>()
    private val sharedSourceCounts = mutableMapOf<String, Flow<List<KindCategoryCount>>>()

    private fun countsForSource(source: String): Flow<List<KindCategoryCount>> = synchronized(sharedSourceCounts) {
        sharedSourceCounts.getOrPut(source) {
            hidden.flatMapLatest { sourceCounts(source, it) }.distinctUntilChanged()
                .stateIn(scope, SharingStarted.Lazily, null).filterNotNull()
        }
    }

    fun categoryCounts(kind: String, source: String): Flow<List<LiveCategoryCount>> = synchronized(sharedCounts) {
        sharedCounts.getOrPut(kind to source) {
            // Lazily stays subscribed between screens. Only catalog invalidation or a changed
            // hidden list reruns SQL; another collector just receives the cached result.
            val groups = if (kind in listOf("LIVE", "MOVIE", "SERIES")) {
                countsForSource(source).map { rows ->
                    rows.filter { it.kind == kind }.map { LiveCategoryCount(it.category, it.count, it.searchKey) }
                }
            } else hidden.flatMapLatest { counts(kind, source, it) }
            groups.distinctUntilChanged()
                .stateIn(scope, SharingStarted.Lazily, null).filterNotNull()
        }
    }
}

/** Safe visibility while a DDL-only upgrade is still filling derived columns. */
internal fun List<Item>.visibleStoredRows(showAdult: Boolean): List<Item> =
    if (showAdult) this else filter { !it.isAdult && it.sortKey.isNotEmpty() }
