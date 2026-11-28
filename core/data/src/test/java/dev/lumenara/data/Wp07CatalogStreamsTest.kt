package dev.lumenara.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Wp07CatalogStreamsTest {
    @Test fun lastObserverStopsSqlAndHiddenCategorySubscriptionWithinOneSecond() = runTest {
        var namesActive = 0
        var countsActive = 0
        val names = flow {
            namesActive++
            try { emit(listOf("News")); awaitCancellation() } finally { namesActive-- }
        }
        val streams = CatalogStreams(names, { _, _, _ -> error("Unexpected fallback") }, { _, _ ->
            flow {
                countsActive++
                try { emit(listOf(KindCategoryCount("MOVIE", "Film", 2))); awaitCancellation() }
                finally { countsActive-- }
            }
        }, flowOf(false), backgroundScope)
        val collector = launch { streams.categoryCounts("MOVIE", "a").collect {} }
        runCurrent()
        assertEquals(1, namesActive)
        assertEquals(1, countsActive)
        collector.cancelAndJoin()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, countsActive)
        assertEquals(0, namesActive)
    }

    @Test fun cacheHasAtMostEightSourceScopesAndEvictionPreservesAnActiveScreen() = runTest {
        val changes = MutableStateFlow(1)
        var activeQueries = 0
        val streams = CatalogStreams(flowOf(emptyList()), { _, _, _ -> error("Unexpected fallback") }, { _, _ ->
            flow {
                activeQueries++
                try { emitAll(changes.map { listOf(KindCategoryCount("MOVIE", "Film", it)) }) }
                finally { activeQueries-- }
            }
        }, flowOf(false), backgroundScope)
        var seen = 0
        val evictedScreen = launch { streams.categoryCounts("MOVIE", "old").collect { seen = it.single().count } }
        runCurrent()
        repeat(20) { source ->
            assertEquals(1, streams.categoryCounts("MOVIE", "scope-$source").first().single().count)
            assertTrue(streams.cachedScopeCount <= 8)
        }
        changes.value = 7
        runCurrent()
        assertEquals("Eviction must not strand an already visible screen", 7, seen)
        evictedScreen.cancelAndJoin()
        advanceTimeBy(500)
        runCurrent()
        assertEquals("Evicted sharing jobs must not survive their last reader", 0, activeQueries)
        assertEquals(8, streams.cachedScopeCount)
        assertEquals("Reopening an evicted scope must get fresh SQL", 7,
            streams.categoryCounts("MOVIE", "old").first().single().count)
    }
}
