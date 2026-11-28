package dev.lumenara.data

import dev.lumenara.catalog.episodeName
import dev.lumenara.catalog.watched
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class Wp14SeriesProjectionTest {
    @Test fun fiveThousandEpisodeProjectionNeverReadsItsInputOnTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        val fixture = (1..5000).map { number -> episode(number) }.reversed()
        val guarded = object : AbstractList<Item>() {
            override val size: Int get() {
                check(Thread.currentThread() !== caller) { "Projection inspected input on Main" }
                threads += Thread.currentThread()
                return fixture.size
            }
            override fun get(index: Int): Item {
                check(Thread.currentThread() !== caller) { "Projection read an episode on Main" }
                threads += Thread.currentThread()
                return fixture[index]
            }
        }
        val catalog = wp14SeriesCatalog(guarded)
        assertTrue(threads.isNotEmpty())
        assertEquals(fixture.size, catalog.items.size)
        assertEquals(50, catalog.seasons.size)
        assertEquals((1..50).toList(), catalog.seasons.keys.toList())
        val expected = withContext(Dispatchers.Default) {
            fixture.sortedWith(compareBy({ it.seriesEpisode().season }, { it.seriesEpisode().episode }, { it.id }))
        }
        assertEquals(expected, catalog.items)
        assertEquals("episode-1", catalog.items.first().id)
        assertEquals("episode-5000", catalog.items.last().id)
        assertEquals(catalog.items[100], catalog.neighbor(catalog.items[99].id, 1))
        assertNull(catalog.neighbor("missing", 1))
        assertNull(catalog.neighbor(catalog.items.last().id, 1))
        val marks = fixture.mapIndexed { index, item ->
            Mark(item.id, position = if (index % 4 == 0) 50 else 100, duration = 100, updated = index.toLong())
        }
        val guardedMarks = object : AbstractList<Mark>() {
            override val size: Int get() {
                check(Thread.currentThread() !== caller) { "Progress inspected marks on Main" }
                return marks.size
            }
            override fun get(index: Int): Mark {
                check(Thread.currentThread() !== caller) { "Progress read a mark on Main" }
                return marks[index]
            }
        }
        val progress = wp14SeriesProgress(catalog, guardedMarks)
        assertEquals(withContext(Dispatchers.Default) { fixture.continueItem(marks) }, progress.continuing)
        assertEquals(marks.associateBy { it.id }, progress.marks)
    }

    @Test fun resumeMatchesExistingRulesForTiesCompletedSeasonsOrphansAndEmptySeries() = runBlocking {
        val rows = listOf(episode(102), episode(1), episode(100), episode(101), episode(2),
            episode(1).copy(id = "same-number-other-id"))
        val catalog = wp14SeriesCatalog(rows)
        val cases = listOf(
            emptyList(),
            listOf(Mark("episode-1", position = 50, duration = 100, updated = 10)),
            listOf(Mark("episode-1", position = 90, duration = 100, updated = 10)),
            listOf(Mark("episode-1", position = 20, duration = 100, updated = 10),
                Mark("episode-101", position = 50, duration = 100, updated = 10)),
            listOf(Mark("episode-100", position = 100, duration = 100, updated = 20),
                Mark("orphan", position = 50, duration = 100, updated = 1000)),
            rows.map { Mark(it.id, position = 100, duration = 100, updated = 10) },
            listOf(Mark("episode-2", position = 20, duration = 0, updated = 10)),
        )
        for (marks in cases) {
            val state = wp14SeriesProgress(catalog, marks)
            assertEquals(withContext(Dispatchers.Default) { rows.continueItem(marks) }, state.continuing)
            assertEquals(marks.any { it.position > 0 }, state.hasHistory)
            val completed = catalog.seasons.filterValues { episodes ->
                episodes.all { item -> marks.firstOrNull { it.id == item.id }?.let {
                    watched(it.position, it.duration)
                } == true }
            }.keys
            assertEquals(completed, state.completedSeasons)
        }
        val endOfSeason = wp14SeriesProgress(catalog,
            listOf(Mark("episode-100", position = 100, duration = 100, updated = 20)))
        assertEquals("episode-101", endOfSeason.continuing?.id)
        assertNull(wp14SeriesProgress(wp14SeriesCatalog(emptyList()), emptyList()).continuing)
    }

    @Test fun providerAndCategoryFallbackNamesAndTieOrderingAreUnchanged() = runBlocking {
        val rows = listOf(episode(1).copy(id = "b"), episode(1).copy(id = "a"),
            episode(2).copy(title = "Example 2. Sezon 3. Bölüm - Arrival"),
            episode(3).copy(title = "Unknown episode", category = "Season 7"))
        val catalog = wp14SeriesCatalog(rows)
        assertEquals(listOf("a", "b"), catalog.items.take(2).map { it.id })
        for (item in rows) assertEquals(episodeName(item.title, item.category), catalog.names[item.id])
        assertEquals(7, catalog.names.getValue("episode-3").season)
        assertEquals(3, catalog.names.getValue("episode-2").episode)
    }

    private fun episode(number: Int) = Item(
        id = "episode-$number", source = "fixture", title = "Example S${(number - 1) / 100 + 1}E${(number - 1) % 100 + 1} - Arrival",
        category = "Series", kind = "EPISODE", tvgId = "", encryptedUrl = "", search = "", generation = 1,
        logo = "", parent = "series",
    )
}
