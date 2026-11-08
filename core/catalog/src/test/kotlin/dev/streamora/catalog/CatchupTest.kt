package dev.streamora.catalog

import org.junit.Assert.*
import org.junit.Test

class CatchupTest {
    @Test
    fun rejectsFutureAndUnsupportedArchive() {
        assertNull(Catchup.address("", 2, 100000, 200000, 300000, "", false))
        assertNull(Catchup.address("https://a.invalid/{utc}", 1, 100000, 200000, 150000, "", false))
        assertNull(Catchup.address("http://a.invalid/{utc}", 1, 100000, 200000, 300000, "", false))
    }

    @Test
    fun archiveUsesActualProgrammeInterval() {
        assertEquals(
            "https://a.invalid/100/100",
            Catchup.address(
                "https://a.invalid/{utc}/{duration}",
                1,
                100000,
                200000,
                300000,
                "",
                false,
            ),
        )
    }
}
