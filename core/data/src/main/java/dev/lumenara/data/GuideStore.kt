package dev.lumenara.data

import dev.lumenara.catalog.searchable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/** Repository lifetime cache: screen/clock/visibility changes never rebuild a resolver. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class GuideStore(
    private val dao: GuideDao,
    private val scope: CoroutineScope,
    private val hidden: Flow<List<String>> = flowOf(emptyList()),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resolvers = mutableMapOf<String, SharedFlow<GuideResolver>>()
    private val cards = mutableMapOf<String, CardPage>()

    fun resolver(source: String): SharedFlow<GuideResolver> = synchronized(resolvers) {
        resolvers.getOrPut(source) {
            combine(
                dao.searchChannels(source).distinctUntilChanged(),
                dao.sourceMappings(source).distinctUntilChanged(),
                dao.sourceNames(source).distinctUntilChanged(),
                dao.programmeChannels(source).distinctUntilChanged(),
            ) { channels, mappings, names, present ->
                GuideResolver(channels, mappings, names, present.toSet())
            }.shareIn(scope, SharingStarted.Lazily, replay = 1)
        }
    }

    fun searchPrograms(source: String, query: String, times: Flow<Pair<Long, Long>>): Flow<List<ProgramHit>> =
        combine(resolver(source), hidden, times.distinctUntilChanged().flatMapLatest { (from, at) ->
            dao.searchSlots(source, searchable(query), from, at).map { Triple(from, at, it) }
        }) { resolver, hidden, timed ->
            resolver.searchPrograms(timed.third, query, timed.first, timed.second, hidden)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // Only collected while the airing category is open. The shared resolver stays
    // subscribed to Room invalidation, while the time-sensitive slot probe is replaced.
    fun nowAiring(source: String, now: Flow<Long>, query: String): Flow<List<AiringProgram>> =
        combine(resolver(source), hidden, now.distinctUntilChanged().flatMapLatest { at ->
            dao.airingSlots(source, at).map { at to it }
        }) { resolver, hidden, timed ->
            resolver.nowAiring(timed.second, timed.first, query).filter { resolver.visible(it.itemId, hidden) }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun cardProgrammes(source: String, itemId: String): Flow<List<Slot>> = synchronized(cards) {
        cards.getOrPut(source) { CardPage(resolver(source)) }.slots(itemId)
    }

    /** Lazy layout composition (including its small prefetch window) defines the page. */
    private inner class CardPage(resolver: SharedFlow<GuideResolver>) {
        private val users = mutableMapOf<String, Int>()
        private val active = MutableStateFlow<Set<String>>(emptySet())

        private fun retain(id: String, delta: Int) = synchronized(users) {
            val count = (users[id] ?: 0) + delta
            if (count == 0) users.remove(id) else users[id] = count
            active.value = users.keys.toSet()
        }

        private val page = active.debounce(16).flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap()) else resolver.map { resolved ->
                ids.associateWith { resolved.targets(it) }
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
                    val byTarget = pages.asSequence().flatten().groupBy { GuideTarget(it.feed, it.channel) }
                    targets.mapValues { (_, pairs) ->
                        pairs.flatMap { byTarget[it].orEmpty() }
                            .sortedWith(compareBy<Slot> { it.start }.thenBy { it.feed })
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
}
