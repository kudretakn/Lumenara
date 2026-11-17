package dev.lumenara.data

import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuideStoreTest {
    private val start = System.currentTimeMillis()
    private val channels = MutableStateFlow((0 until 16).map {
        GuideChannel("i$it", "Channel $it", "c$it", source = "s")
    })
    private val mappings = MutableStateFlow(emptyList<Mapping>())
    private val names = MutableStateFlow(emptyList<GuideName>())
    private val programmes = MutableStateFlow(channels.value.flatMap { item ->
        listOf(Slot("f", item.tvgId, start - 1, start + 30_000, "Current", "", "current"),
            Slot("f", item.tvgId, start + 30_000, start + 120_000, "Next", "", "next"))
    })
    private val reads = mutableMapOf<String, Int>()
    private val pageChannels = mutableListOf<List<String>>()
    private val cardIds = mutableListOf<List<String>>()
    private fun counted(name: String, rows: Flow<*>): Flow<*> = flow {
        reads[name] = (reads[name] ?: 0) + 1
        emitAll(rows)
    }
    private val dao = Proxy.newProxyInstance(GuideDao::class.java.classLoader, arrayOf(GuideDao::class.java)) { _, method, args ->
        val rows = when (method.name) {
            "cardChannels" -> {
                @Suppress("UNCHECKED_CAST") val ids = args!![1] as List<String>
                cardIds += ids
                channels.map { rows -> rows.filter { it.id in ids } }
            }
            "cardTargets" -> {
                val query = args!![0] as androidx.sqlite.db.SupportSQLiteQuery
                val bindings = mutableMapOf<Int, String>()
                val program = Proxy.newProxyInstance(androidx.sqlite.db.SupportSQLiteProgram::class.java.classLoader,
                    arrayOf(androidx.sqlite.db.SupportSQLiteProgram::class.java)) { _, call, values ->
                    if (call.name == "bindString") bindings[values!![0] as Int] = values[1] as String
                    null
                } as androidx.sqlite.db.SupportSQLiteProgram
                query.bindTo(program)
                val ids = (1..query.argCount step 4).map { bindings.getValue(it) }
                combine(channels, mappings, names, programmes) { rows, mappings, names, slots ->
                    val resolver = GuideResolver(rows.filter { it.id in ids }, mappings, names,
                        slots.map { ProgrammeChannel(it.feed, it.channel, "s") }.toSet())
                    ids.flatMap { id -> resolver.targets(id).map { CardGuideTarget(id, it.feed, it.channel) } }
                }
            }
            "searchChannels" -> channels
            "sourceMappings" -> mappings
            "sourceNames" -> names
            "programmeChannels" -> programmes.map { slots ->
                slots.map { ProgrammeChannel(it.feed, it.channel, "s") }.distinct()
            }
            "pageSlots" -> {
                val feed = args!![0] as String
                @Suppress("UNCHECKED_CAST") val ids = args[1] as List<String>
                pageChannels += ids
                val from = args[2] as Long
                val until = args[3] as Long
                programmes.map { rows -> rows.filter { it.feed == feed && it.channel in ids && it.end > from && it.start < until } }
            }
            else -> error("Unexpected guide query: ${method.name}")
        }
        counted(method.name, rows)
    } as GuideDao

    @Test fun sixteenComposedCardsReadOnePageAndSixtyIdleSecondsReadNothing() = runTest {
        var now = start
        val ticks = MutableStateFlow(now)
        val store = GuideStore(dao, backgroundScope, clock = { now })
        val titles = mutableMapOf<String, String?>()
        val jobs = channels.value.map { item ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                combine(store.cardProgrammes("s", item.id), ticks) { rows, at ->
                    rows.firstOrNull { it.start <= at && it.end > at }?.title
                }.collect { titles[item.id] = it }
            }
        }
        advanceTimeBy(17); runCurrent()
        assertEquals(16, titles.size)
        assertTrue(titles.values.all { it == "Current" })
        assertEquals(1, reads["pageSlots"])
        assertEquals(16, pageChannels.single().size)
        assertEquals(16, cardIds.single().size)
        for (query in listOf("searchChannels", "sourceMappings", "sourceNames", "programmeChannels"))
            assertNull("Composed cards must not read whole-source metadata", reads[query])
        val initialReads = reads.toMap()
        repeat(2) {
            advanceTimeBy(30_000)
            now += 30_000
            ticks.value = now
            runCurrent()
        }
        assertTrue(titles.values.all { it == "Next" })
        assertEquals("Clock ticks must not issue any DAO reads", initialReads, reads)
        jobs.forEach(Job::cancel)
    }

    @Test fun cardsSharePagesWithoutStartingTheWholeSourceResolver() = runTest {
        val store = GuideStore(dao, backgroundScope, clock = { start })
        assertSame(store.resolver("s"), store.resolver("s"))
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.cardProgrammes("s", "i0").collect()
        }
        advanceTimeBy(17); runCurrent()
        assertTrue(store.resolver("s").replayCache.isEmpty())
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.cardProgrammes("s", "i0").collect()
        }
        advanceTimeBy(17); runCurrent()
        assertEquals(1, reads["pageSlots"])
        first.cancel(); second.cancel(); runCurrent()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.cardProgrammes("s", "i1").collect() }
        advanceTimeBy(17); runCurrent()
        assertTrue(store.resolver("s").replayCache.isEmpty())
        for (query in listOf("searchChannels", "sourceMappings", "sourceNames", "programmeChannels"))
            assertNull("$query must never start for cards", reads[query])
        assertEquals(listOf("c1"), pageChannels.last())
    }

    @Test fun guideSearchStopsWholeSourceObserversWhenLastCollectorLeaves() = runTest {
        val store = GuideStore(dao, backgroundScope, clock = { start })
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.resolver("s").collect() }
        advanceTimeBy(51); runCurrent()
        assertEquals(1, channels.subscriptionCount.value)
        assertEquals(1, mappings.subscriptionCount.value)
        job.cancel(); runCurrent()
        assertEquals(0, channels.subscriptionCount.value)
        assertEquals(0, mappings.subscriptionCount.value)
        assertTrue(store.resolver("s").replayCache.isEmpty())
    }

    @Test fun oneVisibleCardInTenThousandChannelsReadsOnlyThatCardAndStopsOnExit() = runTest {
        channels.value = channels.value + (16 until 10_645).map {
            GuideChannel("i$it", "Channel $it", "c$it", source = "s")
        }
        val store = GuideStore(dao, backgroundScope, clock = { start })
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.cardProgrammes("s", "i0").collect()
        }
        advanceTimeBy(17); runCurrent()
        assertEquals(listOf(listOf("i0")), cardIds)
        assertEquals(listOf(listOf("c0")), pageChannels)
        assertNull(reads["searchChannels"])
        job.cancel(); runCurrent()
        assertEquals(0, channels.subscriptionCount.value)
        assertEquals(0, programmes.subscriptionCount.value)
        assertEquals(0, mappings.subscriptionCount.value)
    }

    @Test fun mappingAndCatalogueWritesRefreshResolvedTargets() = runTest {
        val store = GuideStore(dao, backgroundScope, clock = { start })
        var slots = emptyList<Slot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.cardProgrammes("s", "i0").collect { slots = it }
        }
        advanceTimeBy(17); runCurrent()
        assertEquals(setOf("c0"), slots.map { it.channel }.toSet())
        mappings.value = listOf(Mapping("i0", "f", "c1"))
        runCurrent()
        assertEquals(setOf("c1"), slots.map { it.channel }.toSet())
        mappings.value = emptyList()
        channels.value = channels.value.map { if (it.id == "i0") it.copy(tvgId = "c2") else it }
        runCurrent()
        assertEquals(setOf("c2"), slots.map { it.channel }.toSet())
    }
}
