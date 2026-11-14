package dev.lumenara.catalog

import org.junit.Assert.*
import org.junit.Test

class SeriesTest {
    private fun ep(id: String, season: Int, episode: Int, position: Long = 0, duration: Long = 100, updated: Long = 0) =
        SeriesEpisode(id, season, episode, position, duration, updated)

    @Test fun ninetyPercentIsInclusiveAndUnknownDurationIsUnfinished() {
        assertFalse(watched(89, 100))
        assertTrue(watched(90, 100))
        assertTrue(watched(101, 100))
        assertFalse(watched(10, 0))
        assertEquals(89L, resumePosition(89, 100))
        assertEquals(0L, resumePosition(90, 100))
        assertEquals(0L, resumePosition(-1, 0))
        assertTrue(watched(1, 1)) // Manual marking when the provider has no duration.
    }

    @Test fun ordersNumericallyAndNextCrossesSeasonBoundary() {
        val rows = listOf(ep("s10", 10, 1), ep("s2", 2, 1), ep("e10", 1, 10), ep("e2", 1, 2))
        assertEquals(listOf("e2", "e10", "s2", "s10"), orderedEpisodes(rows).map { it.id })
        assertEquals("s2", nextEpisode(rows, "e10")?.id)
        assertNull(nextEpisode(rows, "missing"))
        assertNull(nextEpisode(rows, "s10"))
    }

    @Test fun continuePrefersMostRecentlyActivePartialEpisode() {
        val rows = listOf(ep("first", 1, 1, 30, updated = 2), ep("partial", 2, 5, 60, updated = 8),
            ep("completed", 2, 6, 90, updated = 10))
        assertEquals("partial", continueEpisode(rows)?.id)
        assertEquals("partial", continueEpisode(listOf(ep("partial", 1, 1, 7, 0)))?.id)
    }

    @Test fun continueAdvancesAfterMostRecentCompletedEpisodeAndHandlesEnd() {
        val rows = listOf(ep("s2", 2, 1), ep("s1", 1, 10, 100, updated = 5))
        assertEquals("s2", continueEpisode(rows)?.id)
        assertEquals("s1", continueEpisode(rows.map { it.copy(position = 100) })?.id)
        assertEquals("s1", continueEpisode(rows.map { it.copy(position = 0) })?.id)
        assertNull(continueEpisode(emptyList()))
        // If earlier episodes are still unwatched, return to those after reaching the end.
        assertEquals("first", continueEpisode(listOf(ep("first", 1, 1), ep("last", 2, 1, 100, updated = 9)))?.id)
    }

    @Test fun anonymousProviderM3uGroupsTurkishAndSxxEyyTitlesAndHonorsGroupHints() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="Diziler | Türk",Kıyı S01E02
            https://provider.invalid/series/u/p/123.mkv
            #EXTINF:-1 group-title="Diziler | Türk",Kıyı - 1. Sezon 1. Bölüm
            https://provider.invalid/series/u/p/122.mkv
            #EXTINF:-1 group-title="Diziler | Türk",Kıyı S02E01
            https://provider.invalid/no-extension
            #EXTINF:-1 group-title="VOD | Aksiyon",Gece
            https://provider.invalid/video/55.ts
            #EXTINF:-1 group-title="Sinema",Gündüz
            https://provider.invalid/video/56
            #EXTINF:-1 group-title="Haber",Canlı Haber
            https://provider.invalid/live/57.ts
            #EXTINF:-1 group-title="Diziler | Türk",Numarasız Dizi
            https://provider.invalid/series/u/p/124.mkv
        """.trimIndent()
        val parsed = mutableListOf<Listing>()
        Playlist.read(text.reader(), null, false, parsed::add)
        assertEquals(listOf(Kind.EPISODE, Kind.EPISODE, Kind.EPISODE, Kind.MOVIE, Kind.MOVIE, Kind.LIVE, Kind.EPISODE), parsed.map { it.kind })
        val grouper = SeriesGrouper()
        val grouped = mutableListOf<Listing>()
        parsed.forEach { grouper.accept(it, grouped::add) }
        val series = grouped.filter { it.kind == Kind.SERIES }
        assertEquals(listOf("Kıyı", "Numarasız Dizi"), series.map { it.title })
        val episodes = grouped.filter { it.parentUrl == series.first().url }
        assertEquals(3, episodes.size)
        assertEquals(listOf(1, 1, 2), episodes.map { episodeName(it.title).season })
        assertEquals(listOf(2, 1, 1), episodes.map { episodeName(it.title).episode })
        assertEquals("Sezon 2", episodes.last().group)
        assertEquals(1, episodeName(grouped.last().title).episode)
        val reimport = mutableListOf<Listing>()
        SeriesGrouper().accept(parsed.first(), reimport::add)
        assertEquals(series.first().url, reimport.first().url)
    }

    @Test fun acceptsExactAnonymousTitleFormatsAndUppercaseTurkish() {
        assertEquals("Dizi Adı", episodeName("Dizi Adı S01E02").series)
        assertEquals(2, episodeName("Dizi Adı S01E02").episode)
        assertEquals(episodeName("Dizi Adı - 1. Sezon 2. Bölüm").season,
            episodeName("Dizi Adı - 1. SEZON 2. BÖLÜM").season)
        assertEquals(2, episodeName("Dizi Adı - 1. SEZON 2. BÖLÜM").episode)
    }

    @Test fun groupHintsClassifyExtensionlessAndTsStreams() {
        listOf("VOD | Aksiyon", "Film", "Movies", "Sinema").forEach { group ->
            listOf("video/1", "video/1.ts").forEach { path ->
                Playlist.read("#EXTINF:-1 group-title=\"$group\",Bir Film\nhttps://provider.invalid/$path".reader(), null, false) {
                    assertEquals(Kind.MOVIE, it.kind)
                }
            }
        }
        listOf("Diziler | Türk", "Series", "Sezon").forEach { group ->
            Playlist.read("#EXTINF:-1 group-title=\"$group\",Bir Dizi\nhttps://provider.invalid/video/1.ts".reader(), null, false) {
                assertEquals(Kind.EPISODE, it.kind)
            }
        }
    }
}
