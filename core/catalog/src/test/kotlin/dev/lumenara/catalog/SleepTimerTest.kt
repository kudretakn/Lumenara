package dev.lumenara.catalog

import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    @Test fun countsDownAndExpires() {
        var time = 1_000L
        val timer = SleepTimer { time }
        timer.select(SleepTimer.Choice.MINUTES_15)
        assertEquals(900_000L, timer.remainingMs()!!.toLong())
        time += 840_000L
        assertTrue(timer.showCountdown())
        assertFalse(timer.poll())
        time += 60_000L
        assertTrue(timer.poll())
        assertEquals(SleepTimer.Choice.OFF, timer.choice)
    }

    @Test fun extensionAndEpisodeEnd() {
        var time = 0L
        val timer = SleepTimer { time }
        timer.select(SleepTimer.Choice.MINUTES_15)
        time += 15 * 60_000L
        timer.addFifteenMinutes()
        assertEquals(900_000L, timer.remainingMs()!!.toLong())
        timer.select(SleepTimer.Choice.EPISODE)
        assertTrue(timer.episodeEnded())
        assertFalse(timer.episodeEnded())
    }
}
