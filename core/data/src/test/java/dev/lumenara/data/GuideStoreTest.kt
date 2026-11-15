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
    private val start = 1_000_000L
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
    private fun counted(name: String, rows: Flow<*>): Flow<*> = flow {
        reads[name] = (reads[name] ?: 0) + 1
        emitAll(rows)
    }
    private val dao = Proxy.newProxyInstance(GuideDao::class.java.classLoader, arrayOf(GuideDao::class.java)) { _, method, args ->
        val rows = when (method.name) {
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

    @Test fun resolverIsSharedAcrossCardsCollectorsAndScreenReentry() = runTest {
        val store = GuideStore(dao, backgroundScope, clock = { start })
        assertSame(store.resolver("s"), store.resolver("s"))
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.cardProgrammes("s", "i0").collect()
        }
        advanceTimeBy(17); runCurrent()
        val resolver = store.resolver("s").replayCache.single()
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.cardProgrammes("s", "i0").collect()
        }
        advanceTimeBy(17); runCurrent()
        assertEquals(1, reads["pageSlots"])
        first.cancel(); second.cancel(); runCurrent()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.cardProgrammes("s", "i1").collect() }
        advanceTimeBy(17); runCurrent()
        assertSame(resolver, store.resolver("s").replayCache.single())
        for (query in listOf("searchChannels", "sourceMappings", "sourceNames", "programmeChannels"))
            assertEquals("$query must stay subscribed between screens", 1, reads[query])
        assertEquals(listOf("c1"), pageChannels.last())
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
