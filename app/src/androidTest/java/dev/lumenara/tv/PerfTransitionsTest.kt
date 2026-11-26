package dev.lumenara.tv

import org.junit.Assert.*
import org.junit.Test

class PerfTransitionsTest {
    @Test fun firstFrameIncludesRequestResolutionTimeAndEngineOnce() {
        for (engine in listOf("media3", "vlc")) {
            var now = 100L
            val lines = mutableListOf<String>()
            val marks = PerfTransitions({ now }, { lines.add(it) })
            marks.zapStart()
            now = 180 // Debounce and neighbor lookup are part of the request.
            marks.zapSelected("private-channel")
            val request = marks.zapRequest("private-channel")
            assertNull(marks.zapRequest("other-channel"))
            now = 430
            marks.zapFirstFrame(request, engine)
            marks.zapFirstFrame(request, engine)
            assertEquals(listOf("zap_start ms=0", "zap_first_frame ms=330 engine=$engine"), lines)
        }
    }

    @Test fun supersededAndCancelledCallbacksCannotCompleteAnotherRequest() {
        val lines = mutableListOf<String>()
        val marks = PerfTransitions({ 100L }, { lines.add(it) })
        marks.zapStart()
        marks.zapSelected("a")
        val old = marks.zapRequest("a")
        marks.zapStart() // The old frame is rejected even before a new target resolves.
        marks.zapFirstFrame(old, "media3")
        marks.zapSelected("b")
        marks.zapSelected("a") // Same ID and clock time still gets a different token.
        marks.zapFirstFrame(old, "vlc")
        val current = marks.zapRequest("a")
        assertTrue(marks.zapPending(current))
        marks.zapCancel()
        marks.zapFirstFrame(current, "media3")
        assertEquals(listOf("zap_start ms=0", "zap_start ms=0"), lines)
    }

    @Test fun initialChannelSelectionAndEngineFallbackShareOneRequest() {
        var now = 100L
        val lines = mutableListOf<String>()
        val marks = PerfTransitions({ now }, { lines.add(it) })
        marks.zapSelected("initial") // Restored/intent launch without a DPAD request.
        val request = marks.zapRequest("initial")
        now = 600
        marks.zapFirstFrame(request, "vlc") // Media3 failed; VLC completed the request.
        marks.zapFirstFrame(request, "media3")
        assertEquals(listOf("zap_first_frame ms=500 engine=vlc"), lines)
    }

    @Test fun categoryCompletionIsOneShotAndRejectsAbandonedGrids() {
        var now = 10L
        val lines = mutableListOf<String>()
        val marks = PerfTransitions({ now }, { lines.add(it) })
        marks.categoryStart()
        val old = marks.categoryRequest()
        marks.categoryStart()
        val current = marks.categoryRequest()
        marks.categoryCancel(old)
        marks.categoryFirstDraw(old)
        now = 90
        marks.categoryFirstDraw(current)
        marks.categoryFirstDraw(current)
        marks.categoryStart()
        val abandoned = marks.categoryRequest()
        marks.categoryCancel(abandoned)
        marks.categoryFirstDraw(abandoned)
        assertEquals(listOf("category_grid_first_draw ms=80"), lines)
    }
}
