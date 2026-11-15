package dev.lumenara.tv

import dev.lumenara.data.Item
import dev.lumenara.data.Mark
import org.junit.Assert.*
import org.junit.Test

class WatchNextTest {
    private fun item(id: String, title: String = id, kind: String = "MOVIE") =
        Item(id, "source", title, "", kind, "", "", "", 0, "",
            parent = if (kind == "EPISODE") "series" else "")

    @Test fun linkDecodesItemIdsAndPreservesLongPositions() {
        assertEquals(WatchNextPlayback("source:a/b+c", 4_000_000_000),
            parseWatchNextLink("lumenara://play/source%3Aa%2Fb%2Bc?position=4000000000"))
        assertEquals(WatchNextPlayback("film", 0), parseWatchNextLink("lumenara://play/film?position=0"))
    }

    @Test fun malformedLinksAreIgnored() {
        listOf("", "https://play/film?position=0", "lumenara://other/film?position=0",
            "lumenara://play/?position=0", "lumenara://play/a/b?position=0",
            "lumenara://play/a?position=-1", "lumenara://play/a?position=oops",
            "lumenara://play/a?position=9223372036854775808", "lumenara://play/a",
            "lumenara://play/a?position=0&extra=1", "lumenara://play/a?position=0#fragment",
            "lumenara://play/%ZZ?position=0").forEach { assertNull(it, parseWatchNextLink(it)) }
    }

    @Test fun unfinishedMoviesContinueAndCompletedMoviesDisappear() {
        val film = item("film")
        val mark = Mark(film.id, position = 94, duration = 100, updated = 123)
        assertEquals(mark, watchNextEntry(film, mark)?.mark)
        assertFalse(watchNextEntry(film, mark)!!.next)
        assertNull(watchNextEntry(film, mark.copy(position = 95)))
        assertNull(watchNextEntry(film, mark.copy(position = 0)))
        assertNull(watchNextEntry(film.copy(kind = "LIVE"), mark))
    }

    @Test fun completedEpisodeAdvancesAcrossSeasonsAndSkipsWatchedEpisodes() {
        val a = item("a", "Dizi S1E9", "EPISODE")
        val b = item("b", "Dizi S2E1", "EPISODE")
        val c = item("c", "Dizi S2E2", "EPISODE")
        val finished = Mark(a.id, position = 90, duration = 100, updated = 456)
        val result = watchNextEntry(a, finished, listOf(c, a, b),
            listOf(finished, Mark(b.id, position = 100, duration = 100)), "Dizi")!!
        assertEquals(c, result.item)
        assertTrue(result.next)
        assertEquals(0L, result.mark.position)
        assertEquals(456L, result.mark.updated)
        assertEquals("Dizi", result.seriesTitle)
        assertNull(watchNextEntry(c, finished.copy(id = c.id), listOf(a, b, c)))
    }

    @Test fun partiallyWatchedSuccessorUsesContinueRatherThanNext() {
        val a = item("a", "Dizi S1E1", "EPISODE")
        val b = item("b", "Dizi S1E2", "EPISODE")
        val mark = Mark(b.id, position = 25, duration = 100)
        val result = watchNextEntry(a, Mark(a.id, position = 100, duration = 100),
            listOf(a, b), listOf(mark))!!
        assertFalse(result.next)
        assertEquals(25L, result.mark.position)
        assertFalse(watchNextEntry(a, Mark(a.id, position = 89, duration = 100))!!.next)
    }
}
