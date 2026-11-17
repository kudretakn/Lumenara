package dev.lumenara.data

import dev.lumenara.catalog.searchable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/** Visible cards use page-local targets. Whole-source resolvers belong only to guide search. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class GuideStore(
    private val dao: GuideDao,
    private val scope: CoroutineScope,
    private val hidden: Flow<List<String>> = flowOf(emptyList()),
    private val clock: () -> Long = System::currentTimeMillis,
    private val traceWork: (String, Long) -> Unit = { _, _ -> },
) {
    private val resolvers = mutableMapOf<String, SharedFlow<GuideResolver>>()
    private val cards = mutableMapOf<String, CardPage>()
    private data class ResolverInputs(val channels: List<GuideChannel>, val mappings: List<Mapping>,
        val names: List<GuideName>, val present: List<ProgrammeChannel>)

    fun resolver(source: String): SharedFlow<GuideResolver> = synchronized(resolvers) {
        resolvers.getOrPut(source) {
            combine(
                dao.searchChannels(source).distinctUntilChanged(),
                dao.sourceMappings(source).distinctUntilChanged(),
                dao.sourceNames(source).distinctUntilChanged(),
                dao.programmeChannels(source).distinctUntilChanged(),
            ) { channels, mappings, names, present ->
                ResolverInputs(channels, mappings, names, present)
            }.debounce(50).map { inputs ->
                measured("startup_default_work_guide_resolver") {
                    GuideResolver(inputs.channels, inputs.mappings, inputs.names, inputs.present.toSet())
                }
            }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0,
                replayExpirationMillis = 0), replay = 1)
        }
    }

    fun searchPrograms(source: String, query: String, times: Flow<Pair<Long, Long>>): Flow<List<ProgramHit>> =
        combine(resolver(source), hidden, times.distinctUntilChanged().flatMapLatest { (from, at) ->
            dao.searchSlots(source, searchable(query), from, at).map { Triple(from, at, it) }
        }) { resolver, hidden, timed ->
            measured("startup_default_work_guide_search") {
                resolver.searchPrograms(timed.third, query, timed.first, timed.second, hidden)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // Only collected while the airing category is open; release its Room observers on exit.
    fun nowAiring(source: String, now: Flow<Long>, query: String): Flow<List<AiringProgram>> =
        combine(resolver(source), hidden, now.distinctUntilChanged().flatMapLatest { at ->
            dao.airingSlots(source, at).map { at to it }
        }) { resolver, hidden, timed ->
            measured("startup_default_work_guide_airing") {
                resolver.nowAiring(timed.second, timed.first, query).filter { resolver.visible(it.itemId, hidden) }
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun cardProgrammes(source: String, itemId: String): Flow<List<Slot>> = synchronized(cards) {
        cards.getOrPut(source) { CardPage(source) }.slots(itemId)
    }

    /** Lazy layout composition (including its small prefetch window) defines the page. */
    private inner class CardPage(private val source: String) {
        private val users = mutableMapOf<String, Int>()
        private val active = MutableStateFlow<Set<String>>(emptySet())

        private fun retain(id: String, delta: Int) = synchronized(users) {
            val count = (users[id] ?: 0) + delta
            if (count == 0) users.remove(id) else users[id] = count
            active.value = users.keys.toSet()
        }

        private val page = active.debounce(16).flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap()) else {
                // Chunk before binding: Android 9 SQLite permits at most 999 parameters.
                val targetQueries = ids.toList().chunked(200).map { page ->
                    dao.cardChannels(source, page).distinctUntilChanged().flatMapLatest { channels ->
                        if (channels.isEmpty()) flowOf(emptyList()) else {
                            val query = measured("startup_default_work_guide_page_targets") {
                                guidePageTargetsQuery(channels)
                            }
                            dao.cardTargets(query).distinctUntilChanged()
                        }
                    }
                }
                combine(targetQueries) { pages -> pages.asSequence().flatten().groupBy { it.itemId }
                    .mapValues { (_, rows) -> rows.map { GuideTarget(it.feed, it.channel) } }
                }
            }.distinctUntilChanged().flatMapLatest { targets ->
                // Imports already bound programme retention to three future days.
                // Read the remaining page once: even programme/day boundaries need
                // no timer-driven SQL. Room refreshes it when guide data is written.
                val from = clock()
                val feeds = targets.values.flatten().distinct().groupBy { it.feed }
                val queries = feeds.map { (feed, pairs) ->
                    // Stay below Android 9 SQLite's 999 bind-parameter limit even
                    // if a future caller composes an unusually large page.
                    pairs.map { it.channel }.chunked(900).map { channels ->
                        dao.pageSlots(feed, channels, from, Long.MAX_VALUE)
                    }
                }.flatten()
                if (queries.isEmpty()) flowOf(emptyMap()) else combine(queries) { pages ->
                    measured("startup_default_work_guide_page_merge") {
                        val byTarget = pages.asSequence().flatten().groupBy { GuideTarget(it.feed, it.channel) }
                        targets.mapValues { (_, pairs) ->
                            pairs.flatMap { byTarget[it].orEmpty() }
                                .sortedWith(compareBy<Slot> { it.start }.thenBy { it.feed })
                        }
                    }
                }
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), emptyMap())

        fun slots(id: String): Flow<List<Slot>> = flow {
            retain(id, 1)
            try {
                emitAll(page.map { it[id].orEmpty() }.distinctUntilChanged())
            } finally {
                retain(id, -1)
            }
        }
    }

    private inline fun <T> measured(event: String, block: () -> T): T {
        val start = System.nanoTime()
        return try { block() } finally { traceWork(event, (System.nanoTime() - start) / 1_000_000) }
    }
}
