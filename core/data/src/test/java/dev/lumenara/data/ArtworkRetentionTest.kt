package dev.lumenara.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ArtworkRetentionTest {
    @Test fun scrollingMemoryHitsNeverTouchDisk() = runTest {
        var diskReads = 0
        var reloads = 0
        repeat(1000) {
            assertEquals("memory", retainArtworkResult("memory", false,
                expired = { diskReads++; true }, reload = { reloads++; "network" }))
        }
        assertEquals(0, diskReads)
        assertEquals(0, reloads)
    }

    @Test fun onlyExpiredDiskHitsAreReloaded() = runTest {
        var reloads = 0
        for (expired in listOf(false, true)) {
            assertEquals(if (expired) "network" else "disk", retainArtworkResult("disk", true,
                expired = { expired }, reload = { reloads++; "network" }))
        }
        assertEquals(1, reloads)
    }
}
