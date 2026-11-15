package dev.lumenara.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RepositoryTrimTest {
    @Test fun trimClearsCachesAndUnsubscribedOrderStopsAfterGracePeriod() = runTest {
        var upstreamStopped = false
        val orders: StateFlow<List<LiveChannelId>> = flow<List<LiveChannelId>> {
            try {
                emit(emptyList())
                awaitCancellation()
            } finally {
                upstreamStopped = true
            }
        }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(30_000, replayExpirationMillis = 0), emptyList())

        val repository = uninitializedRepository()
        val liveOrders = mutableMapOf("source" to orders)
        val numberSnapshots = ConcurrentHashMap<String, Any>()
            .apply { put("source", Any()) }
        setField(repository, "liveOrders", liveOrders)
        setField(repository, "liveNumberSnapshots", numberSnapshots)

        val collector = launch { orders.collect {} }
        runCurrent()
        collector.cancelAndJoin()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue("upstream should stop when its last subscriber leaves", upstreamStopped)

        Repository::class.java.getDeclaredMethod("trim").apply { isAccessible = true }.invoke(repository)
        assertTrue(liveOrders.isEmpty())
        assertTrue(numberSnapshots.isEmpty())
    }

    private fun uninitializedRepository(): Repository {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Repository::class.java) as Repository
    }

    private fun setField(target: Any, name: String, value: Any) {
        Repository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
