package dev.lumenara.tv

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class Wp05HubReadinessTest {
    @Test fun delayedCountsAndArtworkCannotProduceFullHub() {
        val hub = Wp05HubDraw().apply { viewport = Rect(0f, 0f, 100f, 100f) }
        val image = Wp05HubDraw.Image().apply { bounds = Rect(0f, 0f, 50f, 50f) }
        hub.add(image)
        val events = mutableListOf<String>()
        hub.onDraw(true, false, true, events::add)
        assertFalse(events.contains("startup_hub_full_draw"))
        hub.onDraw(true, true, true, events::add)
        assertTrue(events.contains("startup_hub_local_draw"))
        assertFalse(events.contains("startup_hub_full_draw"))
        image.terminalDrawn = true // Success or a real error/missing fallback has drawn.
        hub.onDraw(true, true, false, events::add)
        assertFalse(events.contains("startup_hub_full_draw"))
        hub.onDraw(true, true, true, events::add)
        hub.onDraw(true, true, true, events::add)
        assertEquals(1, events.count { it == "startup_hub_full_draw" })
    }

    @Test fun countsStillBlockFullAfterArtworkHasDrawnAndRowsStillBlockEmptyHub() {
        val hub = Wp05HubDraw().apply { viewport = Rect(0f, 0f, 100f, 100f) }
        val events = mutableListOf<String>()
        hub.onDraw(false, true, true, events::add)
        assertFalse(events.contains("startup_hub_full_draw"))
        hub.onDraw(true, false, true, events::add)
        assertTrue(events.contains("startup_hub_artwork_draw"))
        assertFalse(events.contains("startup_hub_full_draw"))
        hub.onDraw(true, true, true, events::add)
        assertTrue(events.contains("startup_hub_full_draw"))
    }

    @Test fun precomposedOffscreenImagesDoNotBlockButVisibleLoadingDoes() {
        val hub = Wp05HubDraw().apply { viewport = Rect(0f, 0f, 100f, 100f) }
        val image = Wp05HubDraw.Image().apply { bounds = Rect(0f, 110f, 50f, 150f) }
        hub.add(image)
        assertTrue(hub.artworkDrawn())
        image.bounds = Rect(0f, 90f, 50f, 150f)
        assertFalse(hub.artworkDrawn())
        hub.remove(image)
        assertTrue(hub.artworkDrawn())
        assertFalse(Wp05HubDraw().artworkDrawn())
    }
}
