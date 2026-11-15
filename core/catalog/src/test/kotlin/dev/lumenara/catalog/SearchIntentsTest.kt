package dev.lumenara.catalog

import org.junit.Assert.*
import org.junit.Test

class SearchIntentsTest {
    @Test fun supportsSystemGoogleAndMediaSearchActions() {
        assertEquals(SearchRequest("TRT 1", false), searchRequest("android.intent.action.SEARCH", " TRT 1 "))
        assertEquals(SearchRequest("TRT 1", false), searchRequest("com.google.android.gms.actions.SEARCH_ACTION", "TRT 1"))
        assertEquals(SearchRequest("TRT 1", true), searchRequest("android.media.action.MEDIA_PLAY_FROM_SEARCH", "TRT 1"))
        assertNull(searchRequest("unknown", "TRT 1"))
        assertNull(searchRequest("android.intent.action.SEARCH", "  "))
    }

    @Test fun onlyReturnsUniqueNormalizedExactChannelMatch() {
        assertEquals(1, exactChannelIndex("trt 1", listOf("TRT Haber", "TRT 1")))
        assertNull(exactChannelIndex("trt", listOf("TRT 1", "TRT Haber")))
        assertNull(exactChannelIndex("TRT 1", listOf("TRT 1", "trt 1")))
    }
}
