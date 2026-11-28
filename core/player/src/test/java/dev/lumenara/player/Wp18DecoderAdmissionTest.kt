package dev.lumenara.player

import org.junit.Assert.assertEquals
import org.junit.Test

class Wp18DecoderAdmissionTest {
    @Test fun unknownHevc4kAndHighRateNeverInheritAvcInstanceCount() {
        val hd = Wp18VideoLoad("video/avc", 1920, 1080, 30f)
        for (load in listOf(null, hd.copy(mime = "video/hevc"), hd.copy(width = 3840, height = 2160),
                hd.copy(frameRate = 60f), hd.copy(frameRate = Float.NaN), hd.copy(width = -1))) {
            assertEquals(1, wp18DecoderWindow(16, load))
        }
        assertEquals(2, wp18DecoderWindow(16, hd))
        assertEquals(1, wp18DecoderWindow(1, hd))
    }

    @Test fun decoderFailureMonotonicallyReducesAdmission() {
        val capacity = DecoderCapacity { 4 }
        assertEquals(4, capacity.get())
        assertEquals(2, capacity.failed(3))
        assertEquals(2, capacity.get())
        assertEquals(1, capacity.failed(2))
        assertEquals(1, capacity.failed(4))
        assertEquals(1, capacity.get())
    }
}
