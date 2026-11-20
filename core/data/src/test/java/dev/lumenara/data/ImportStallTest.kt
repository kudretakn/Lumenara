package dev.lumenara.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImportStallTest {
    @get:org.junit.Rule val cache = org.junit.rules.TemporaryFolder()

    @Test fun appendingApiToShortLinkFailsInsteadOfReachingProvider() = MockWebServer().use { provider ->
        MockWebServer().use { shortener ->
            shortener.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/vip")
                    MockResponse().setResponseCode(302).addHeader("Location", provider.url("/prefix/"))
                else MockResponse().setResponseCode(404)
            }
            val spec = SourceSpec(shortener.url("/vip").toString(), true, "u", "p")
            val error = runCatching { Xtream(spec).prepare(cache.root, FetchState()) }.exceptionOrNull()
            assertTrue(error is SourceFailure)
            assertTrue(shortener.takeRequest().path!!.startsWith("/vip/player_api.php?"))
            assertEquals(0, provider.requestCount)
        }
    }

    @Test fun tricklingDownloadFailsThroughputWindowAndRemovesPartialBody() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 40, TimeUnit.MILLISECONDS))
        val started = System.nanoTime()
        val error = runCatching {
            Transport.download(server.url("/").toString(), true, cache.root,
                stallWindowMillis = 200).close()
        }.exceptionOrNull()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(error is SourceFailure)
        assertTrue(error!!.message!!.contains("zaman aşımına"))
        assertTrue(elapsedMillis >= 200)
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }

    @Test fun sustainedDownloadCanOutliveThroughputWindow() = MockWebServer().use { server ->
        val body = "x".repeat(256 * 1024)
        server.enqueue(MockResponse().setBody(body).throttleBody(32768, 40, TimeUnit.MILLISECONDS))
        val started = System.nanoTime()
        Transport.download(server.url("/").toString(), true, cache.root,
            stallWindowMillis = 200).use { download ->
            assertEquals(body, download.read { input, _ -> input.reader().readText() })
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 200)
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }

    @Test fun earlyBurstDoesNotHideLaterTrickle() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 40, TimeUnit.MILLISECONDS))
        val started = System.nanoTime()
        // A small threshold lets the first window pass; the following window must still fail.
        val error = runCatching {
            Transport.download(server.url("/").toString(), true, cache.root,
                stallWindowMillis = 200, stallMinimumBytes = 6,
                progress = { if (it == 0L) Thread.sleep(250) }).close()
        }.exceptionOrNull()
        assertTrue(error is SourceFailure)
        assertTrue(error!!.message!!.contains("zaman aşımına"))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 450)
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }

    @Test fun heldRepositoryMutexPreventsAnyNetworkRequest() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val lock = Mutex(locked = true)
            val add = async { lock.withLock { Transport.read(server.url("/").toString(), true) { _, _ -> Unit } } }
            runCurrent()
            assertFalse(add.isCompleted)
            assertEquals(0, server.requestCount)
            add.cancel()
            add.join()
            lock.unlock()
        }
    }
}
