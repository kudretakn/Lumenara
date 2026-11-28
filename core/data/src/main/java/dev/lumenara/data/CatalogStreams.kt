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
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(0, replayExpirationMillis = 0), null).filterNotNull()

    private class SourceStreams(val counts: Flow<List<KindCategoryCount>>) {
        val kinds = mutableMapOf<String, Flow<List<LiveCategoryCount>>>()
    }
    // One cache entry per source scope (including all-source). No sharing job survives
    // an evicted scope's last observer; active screens retain their own valid flow.
    private val sources = LinkedHashMap<String, SourceStreams>(8, .75f, true)
    internal val cachedScopeCount: Int get() = synchronized(sources) { sources.size }

    fun categoryCounts(kind: String, source: String): Flow<List<LiveCategoryCount>> = synchronized(sources) {
        val entry = sources.getOrPut(source) {
            SourceStreams(hidden.flatMapLatest { sourceCounts(source, it) }
                .distinctUntilChanged().wp07Observed(scope))
        }
        while (sources.size > 8) sources.remove(sources.keys.first())
        entry.kinds.getOrPut(kind) {
            val groups = if (kind in listOf("LIVE", "MOVIE", "SERIES")) {
                entry.counts.map { rows ->
                    rows.filter { it.kind == kind }.map { LiveCategoryCount(it.category, it.count, it.searchKey) }
                }
            } else hidden.flatMapLatest { counts(kind, source, it) }.wp07Observed(scope)
            groups.distinctUntilChanged()
        }
    }
}

/** Safe visibility while a DDL-only upgrade is still filling derived columns. */
internal fun List<Item>.visibleStoredRows(showAdult: Boolean): List<Item> =
    if (showAdult) this else filter {
        if (it.sortKey.isEmpty()) !isAdultCategory(it.category) else !it.isAdult
    }
