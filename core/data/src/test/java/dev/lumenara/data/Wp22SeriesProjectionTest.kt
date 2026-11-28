package dev.lumenara.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class Wp22SeriesProjectionTest {
    @Test fun fiveThousandReadyRowsUseStoredNamesAndAlreadyOrderedItems(): Unit = runBlocking {
        val rows = (1..5000).map { episode(it).wp22ProjectEpisode() }
        // Provider text is deliberately unusable: ready projections must not parse it again.
        val stored = rows.map { it.copy(title = "not numbered", category = "not a season") }
        val catalog = wp22SeriesCatalog(stored)
        assertSame(stored, catalog.items)
        assertEquals((1..50).toList(), catalog.seasons.keys.toList())
        assertEquals(100, catalog.names.getValue("ep-5000").episode)
        assertEquals("Arrival", catalog.titles["ep-5000"])
        assertEquals(stored[100], catalog.neighbor("ep-100", 1))
    }

    @Test fun readyResumeEqualsLegacyForTiesUnknownDurationsCompletedSeasonsAndOrphans(): Unit = runBlocking {
        val raw = (1..205).map(::episode) + episode(1).copy(id = "duplicate-number")
        val legacy = wp14SeriesCatalog(raw)
        val ready = wp22SeriesCatalog(legacy.items.map { it.wp22ProjectEpisode() })
        val random = Random(22)
        val cases = listOf(emptyList(), raw.map { Mark(it.id, position = 90, duration = 100, updated = 1) },
            listOf(Mark("ep-100", position = 90, duration = 100, updated = 9))) + (1..50).map {
            raw.map { Mark(it.id, position = listOf(0L, 10L, 89L, 90L, 100L)[random.nextInt(5)],
                duration = if (random.nextBoolean()) 100 else 0, updated = random.nextLong(4)) } +
                Mark("orphan", position = 20, duration = 100, updated = 1000)
        }
        for (marks in cases) {
            val expected = wp14SeriesProgress(legacy, marks)
            val actual = wp22SeriesProgress(ready, marks)
            assertEquals(expected.continuing?.id, actual.continuing?.id)
            assertEquals(expected.hasHistory, actual.hasHistory)
            assertEquals(expected.completedSeasons, actual.completedSeasons)
        }
        assertNull(wp22SeriesProgress(wp22SeriesCatalog(emptyList()), emptyList()).continuing)
    }

    @Test fun partiallyBackfilledSeriesKeepsLegacyOrderingAndNames(): Unit = runBlocking {
        val rows = listOf(episode(102), episode(2).wp22ProjectEpisode(), episode(1))
        val expected = wp14SeriesCatalog(rows)
        val actual = wp22SeriesCatalog(rows)
        assertEquals(expected, actual)
    }

    private fun episode(number: Int) = Item("ep-$number", "source",
        "Show S${(number - 1) / 100 + 1}E${(number - 1) % 100 + 1} - Arrival", "Series", "EPISODE",
        "", "", "", 1, "", parent = "series")
}
