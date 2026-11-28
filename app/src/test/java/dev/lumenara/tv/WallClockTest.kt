package dev.lumenara.tv

import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WallClockTest {
    @Test fun realTimeTicksKeepUnconfinedUiCollectorsOnMain() {
        Executors.newSingleThreadExecutor { Thread(it, "wall-clock-main") }
            .asCoroutineDispatcher().use { main ->
                Dispatchers.setMain(main)
                try {
                    runBlocking(main) {
                        val uiThread = Thread.currentThread()
                        val scheduler = TestCoroutineScheduler()
                        var ticks = 0
                        withTimeout(5_000) {
                            // Compose's test effects use an unconfined dispatcher. A background
                            // emitter can resume them inline, including snapshot apply work.
                            withContext(UnconfinedTestDispatcher(scheduler)) {
                                wallClockTicks(25).take(3).collect {
                                    assertSame("Timer collectors must stay on Main", uiThread, Thread.currentThread())
                                    ticks++
                                }
                                assertSame(uiThread, Thread.currentThread())
                            }
                        }
                        // Receiving later ticks without advancing virtual time also exercises
                        // real delays and take() cancelling the otherwise infinite producer.
                        assertEquals(3, ticks)
                        assertEquals(0L, scheduler.currentTime)
                    }
                } finally {
                    Dispatchers.resetMain()
                }
            }
    }
}
