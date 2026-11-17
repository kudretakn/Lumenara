package dev.lumenara.tv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveZapDirectionTest {
    @Test fun liveChannelAndPageKeysMapToZapDirections() {
        assertEquals(-1, liveZapDirection(KeyEvent.KEYCODE_CHANNEL_UP, true))
        assertEquals(-1, liveZapDirection(KeyEvent.KEYCODE_PAGE_UP, true))
        assertEquals(1, liveZapDirection(KeyEvent.KEYCODE_CHANNEL_DOWN, true))
        assertEquals(1, liveZapDirection(KeyEvent.KEYCODE_PAGE_DOWN, true))
    }

    @Test fun dpadMappingIsPreservedAndNonLiveItemsIgnoreZapKeys() {
        assertEquals(-1, liveZapDirection(KeyEvent.KEYCODE_DPAD_UP, true))
        assertEquals(1, liveZapDirection(KeyEvent.KEYCODE_DPAD_DOWN, true))
        listOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
            .forEach { assertNull(liveZapDirection(it, false)) }
    }
}
