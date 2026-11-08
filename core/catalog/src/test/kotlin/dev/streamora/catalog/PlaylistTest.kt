package dev.streamora.catalog

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class PlaylistTest {
    private fun parse(text: String, http: Boolean = false) = buildList {
        Playlist.read(text.reader(), URI("https://example.invalid/lists/main"), http, ::add)
    }

    @Test
    fun parsesQuotedCommasAndRelativeLinks() {
        val item =
            parse(
                    "\uFEFF#EXTM3U\n#EXTINF:-1 tvg-id=\"7\" group-title=\"TV, Spor\",Maç, Canlı\n../live/7\n"
                )
                .single()
        assertEquals(
            Listing("Maç, Canlı", "https://example.invalid/live/7", "TV, Spor", "7", Kind.LIVE),
            item,
        )
    }

    @Test
    fun rejectsUnsafeSchemesAndResetsBrokenMetadata() {
        assertEquals(
            "Adsız yayın",
            parse(
                    "#EXTINF:-1,Kötü\nhttp://old.invalid/a\nfile:///storage/a\nbad link\nhttps://example.invalid/b"
                )
                .single()
                .title,
        )
        assertEquals(1, parse("http://old.invalid/a", true).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHlsSegments() {
        parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\npart.ts")
    }

    @Test
    fun keepsSourceIdentitiesSeparate() {
        assertNotEquals(contentKey("a", "u"), contentKey("b", "u"))
        assertEquals(contentKey("a", "u"), contentKey("a", "u"))
    }

    @Test
    fun normalizesTurkishSearch() {
        assertEquals("isik spor", searchable("IŞIK Spor"))
    }

    @Test
    fun readsLargePlaylistIncrementally() {
        val data = buildString {
            repeat(20_000) { append("https://example.invalid/live/").append(it).append('\n') }
        }
        var count = 0
        Playlist.read(data.reader(), null, false) { count++ }
        assertEquals(20_000, count)
    }
}
