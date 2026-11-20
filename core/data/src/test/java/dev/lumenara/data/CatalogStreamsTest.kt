package dev.lumenara.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogStreamsTest {
    @Test fun unfilledUpgradeRowsUseOriginalCategoriesWithoutHidingSafeContent() {
        val safe = Item("safe", "source", "News", "News", "LIVE", "", "sealed", "news", 1, "")
        val adult = safe.copy(id = "adult", category = "XXX", isAdult = true)
        val legacy = safe.copy(id = "legacy", sortKey = "", isAdult = false)
        val legacyAdult = adult.copy(id = "legacy-adult", sortKey = "", isAdult = false)
        val rows = listOf(safe, adult, legacy, legacyAdult)
        assertEquals(listOf(safe, legacy), rows.visibleStoredRows(false))
        assertEquals(rows, rows.visibleStoredRows(true))
        val filled = legacy.copy(sortKey = "news")
        assertEquals(listOf(safe, filled), listOf(safe, adult, filled).visibleStoredRows(false))
    }

    @Test fun pendingReadEmitsContentAndSwitchesOnlyWhenReadinessChanges() = runTest {
        val ready = MutableStateFlow(false)
        val original = MutableStateFlow(listOf("provider row"))
        var fallbackReads = 0
        var storedReads = 0
        val dao = java.lang.reflect.Proxy.newProxyInstance(LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java)) { _, method, args ->
            check(method.name == "catalogReady")
            assertEquals("pending", args!![0])
            ready
        } as LibraryDao
        val rows = dao.withCatalog("pending") { done ->
            if (done) { storedReads++; flowOf(listOf("stored row")) }
            else { fallbackReads++; original }
        }.stateIn(backgroundScope, SharingStarted.Eagerly, null)
        runCurrent()
        assertEquals(listOf("provider row"), rows.value)
        original.value = listOf("provider row", "next batch")
        runCurrent()
        assertEquals(2, rows.value!!.size)
        assertEquals(1, fallbackReads)
        assertEquals(0, storedReads)
        ready.value = true
        runCurrent()
        assertEquals(listOf("stored row"), rows.value)
        assertEquals(1, storedReads)
    }

    @Test fun catalogReadWaitsForReadinessAndRealDatabaseEmissionOnBothPaths() = runTest {
        for (done in listOf(false, true)) {
            for (expected in listOf(emptyList(), listOf("real database row"))) {
                val readiness = MutableSharedFlow<Boolean>(replay = 1)
                val database = MutableSharedFlow<List<String>>(replay = 1)
                var selectedPath: Boolean? = null
                val dao = java.lang.reflect.Proxy.newProxyInstance(LibraryDao::class.java.classLoader,
                    arrayOf(LibraryDao::class.java)) { _, method, args ->
                    check(method.name == "catalogReady")
                    assertEquals("source", args!![0])
                    readiness
                } as LibraryDao
                val first = async {
                    dao.withCatalog("source") { ready ->
                        flow { selectedPath = ready; emitAll(database) }
                    }.first()
                }
                runCurrent()
                assertNull(selectedPath)
                assertFalse(first.isCompleted)
                readiness.emit(done)
                runCurrent()
                assertEquals(done, selectedPath)
                assertFalse("Readiness alone must not emit an empty result", first.isCompleted)
                database.emit(expected)
                assertEquals(expected, first.await())
            }
        }
    }

    private class Catalog {
        val names = MutableStateFlow(listOf("Film", "For Adults"))
        val showAdult = MutableStateFlow(false)
        var nameReads = 0
        val reads = mutableMapOf<String, Int>()
        var otherReads = 0
        val rows = mutableMapOf<String, MutableStateFlow<List<KindCategoryCount>>>()

        fun streams(scope: CoroutineScope) = CatalogStreams(
            flow { nameReads++; emitAll(names) },
            { _, _, _ -> flow { otherReads++; emit(listOf(LiveCategoryCount("Other", 7))) } },
            { source, hidden ->
                flow {
                    reads[source] = reads.getOrDefault(source, 0) + 1
                    emitAll(rows.getOrPut(source) {
                        MutableStateFlow(listOf("LIVE", "MOVIE", "SERIES").flatMap { kind ->
                            listOf(KindCategoryCount(kind, "Film", 2), KindCategoryCount(kind, "For Adults", 1))
                        })
                    }.map { groups -> groups.filterNot { it.category in hidden } })
                }
            },
            showAdult, scope,
        )
    }

    @Test fun hubMetadataLeavesTwoQueriesForHistoryShelvesAndReusesCountsAcrossScreens() = runTest {
        val catalog = Catalog()
        val streams = catalog.streams(backgroundScope)
        val kinds = listOf("LIVE", "MOVIE", "SERIES")
        // These are the exact three flows consumed by Hub totals.
        for (kind in kinds) assertEquals(2, streams.categoryCounts(kind, "").first().sumOf { it.count })
        // One name query + one grouped count query; Hub's two shelves bring the total to four.
        assertEquals(2, catalog.nameReads + catalog.reads.values.sum())
        repeat(18) { screen ->
            val kind = kinds[screen % kinds.size]
            assertSame(streams.categoryCounts(kind, ""), streams.categoryCounts(kind, ""))
            assertEquals(listOf("Film"), streams.categoryCounts(kind, "").first().map { it.category })
            assertEquals(listOf("For Adults"), streams.hidden.first())
        }
        assertEquals(2, catalog.nameReads + catalog.reads.values.sum())

        // Source-specific category screens own one additional flow, also shared on return.
        repeat(2) { streams.categoryCounts("MOVIE", "a").first() }
        assertEquals(1, catalog.reads["a"])
        assertEquals(1, catalog.nameReads)
        // Preserve the general API for callers outside the three top-level content kinds.
        repeat(2) { assertEquals(7, streams.categoryCounts("EPISODE", "a").first().single().count) }
        assertEquals(1, catalog.otherReads)
    }

    @Test fun visibilityWaitsForBothInputsBeforeStartingCounts() = runTest {
        val names = MutableSharedFlow<List<String>>(replay = 1)
        val showAdult = MutableSharedFlow<Boolean>(replay = 1)
        var queries = 0
        val streams = CatalogStreams(names, { _, _, _ -> error("Unexpected fallback query") }, { _, hidden ->
            flow {
                queries++
                assertEquals(listOf("For Adults"), hidden)
                emit(listOf(KindCategoryCount("MOVIE", "Film", 1)))
            }
        }, showAdult, backgroundScope)
        val hidden = async { streams.hidden.first() }
        val groups = async { streams.categoryCounts("MOVIE", "").first() }
        runCurrent()
        assertFalse(hidden.isCompleted)
        assertFalse(groups.isCompleted)
        assertEquals(0, queries)
        names.emit(listOf("Film", "For Adults"))
        runCurrent()
        assertFalse(hidden.isCompleted)
        assertEquals(0, queries)
        showAdult.emit(false)
        assertEquals(listOf("For Adults"), hidden.await())
        assertEquals(1, groups.await().single().count)
        assertEquals(1, queries)
    }

    @Test fun preferencesAndCatalogInvalidationUpdateExistingSharedFlows() = runTest {
        val catalog = Catalog()
        val streams = catalog.streams(backgroundScope)
        val groups = streams.categoryCounts("MOVIE", "")
        assertEquals(2, groups.first().sumOf { it.count })
        catalog.showAdult.value = true
        runCurrent()
        assertEquals(3, groups.first().sumOf { it.count })
        catalog.showAdult.value = false
        runCurrent()
        assertEquals(2, groups.first().sumOf { it.count })
        assertEquals(3, catalog.reads[""])

        // Room emits new counts even while no screen is collecting the cached flow.
        catalog.rows.getValue("").value = listOf(KindCategoryCount("MOVIE", "Film", 4))
        runCurrent()
        assertEquals(4, groups.first().single().count)
        // An ordinary category change must not restart SQL when the hidden set is unchanged.
        catalog.names.value = catalog.names.value + "Drama"
        runCurrent()
        assertEquals(3, catalog.reads[""])
        catalog.names.value = catalog.names.value + "XXX"
        runCurrent()
        assertEquals(listOf("For Adults", "XXX"), streams.hidden.first())
        assertEquals(4, catalog.reads[""])
        assertEquals(1, catalog.nameReads)
    }
}
