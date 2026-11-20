package dev.lumenara.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SourceImportTest {
    @get:org.junit.Rule val cache = org.junit.rules.TemporaryFolder()

    @Test fun bareBaseRedirectsOnceAndAllXtreamCallsUseResolvedPrefix() {
        for (code in listOf(301, 302)) MockWebServer().use { shortener ->
            MockWebServer().use { provider ->
                shortener.enqueue(MockResponse().setResponseCode(code)
                    .addHeader("Location", provider.url("/prefix/?token=private#fragment")))
                provider.enqueue(MockResponse().setBody("web page"))
                provider.enqueue(MockResponse().setBody("""{"user_info":{"auth":1}}"""))
                repeat(6) { provider.enqueue(MockResponse().setBody("[]")) }
                val address = Transport.resolveBase(shortener.url("/vip").toString(), true)
                assertEquals(provider.url("/prefix").toString(), address)
                Xtream(SourceSpec(address, true, "u", "p")).prepare(cache.root, FetchState()).close()
                assertEquals("/vip", shortener.takeRequest().path)
                assertEquals(1, shortener.requestCount)
                assertEquals("/prefix/?token=private", provider.takeRequest().path)
                repeat(7) {
                    val request = provider.takeRequest()
                    assertEquals("/prefix/player_api.php", request.requestUrl!!.encodedPath)
                    assertEquals("u", request.requestUrl!!.queryParameter("username"))
                    assertNull(request.requestUrl!!.queryParameter("token"))
                }
                assertTrue(cache.root.listFiles()!!.isEmpty())
            }
        }
    }

    @Test fun resolutionFollowsMultipleRedirectsAndKeepsTheFinalPortAndPath() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(301).addHeader("Location", "/next"))
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/panel/root/"))
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals(server.url("/panel/root").toString(), Transport.resolveBase(server.url("/vip").toString(), true))
    }

    @Test fun httpDisabledResolutionMakesNoRequest() = MockWebServer().use { server ->
        val error = runCatching { Transport.resolveBase(server.url("/secret").toString(), false) }.exceptionOrNull()
        assertTrue(error is SourceFailure)
        assertTrue(error!!.message!!.contains("HTTP uyumluluğunu açın"))
        assertFalse(error.message!!.contains("secret"))
        assertEquals(0, server.requestCount)
    }

    @Test fun invalidAndFailedResolutionHaveSafeTurkishErrors() {
        val invalid = runCatching { Transport.resolveBase("invalid secret", true) }.exceptionOrNull()
        assertTrue(invalid is SourceFailure)
        assertTrue(invalid!!.message!!.contains("Sunucu adresi çözümlenemedi"))
        MockWebServer().use { server ->
            for (code in listOf(302, 503)) {
                server.enqueue(MockResponse().setResponseCode(code))
                val error = runCatching { Transport.resolveBase(server.url("/secret").toString(), true) }.exceptionOrNull()
                assertTrue(error is SourceFailure)
                assertTrue(error!!.message!!.contains("Sunucu adresi çözümlenemedi"))
                assertFalse(error.message!!.contains("secret"))
            }
        }
    }

    @Test fun providerWithoutRootPageCanStillResolve() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(server.url("/").toString().trimEnd('/'), Transport.resolveBase(server.url("/").toString(), true))
    }

    @Test fun resolvedBaseContainsOnlySchemeHostPortAndPrefix() = MockWebServer().use { server ->
        val target = server.url("/prefix/?token=secret#fragment").newBuilder()
            .username("embedded").password("private").build()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target))
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals(server.url("/prefix").toString(), Transport.resolveBase(server.url("/vip").toString(), true))
    }

    @Test fun sharedClientHasNoTotalDeadlineAndKeepsReadTimeout() {
        assertEquals(0, Transport.client(true).callTimeoutMillis)
        assertEquals(25_000, Transport.client(true).readTimeoutMillis)
    }

    @Test fun shortenerRedirectToXtreamFileResolvesToProviderRoot() = MockWebServer().use { shortener ->
        MockWebServer().use { provider ->
            shortener.enqueue(MockResponse().setResponseCode(302).addHeader("Location",
                provider.url("/get.php?username=u&password=p&type=m3u_plus")))
            provider.enqueue(MockResponse().setBody("playlist"))
            assertEquals(provider.url("/").toString().trimEnd('/'),
                Transport.resolveBase(shortener.url("/vip").toString(), true))
        }
    }

    @Test fun shortenerRedirectToFileKeepsProviderPrefix() = MockWebServer().use { shortener ->
        MockWebServer().use { provider ->
            for (file in listOf("player_api.php", "xmltv.php", "panel_api.php", "index.html")) {
                shortener.enqueue(MockResponse().setResponseCode(302)
                    .addHeader("Location", provider.url("/prefix/$file")))
                provider.enqueue(MockResponse().setBody("ok"))
                assertEquals(provider.url("/prefix").toString(),
                    Transport.resolveBase(shortener.url("/vip").toString(), true))
            }
        }
    }

    @Test fun queuedAddReportsWaitingAndCanBeCancelledWithoutStarting() = runBlocking {
        val lock = Mutex(locked = true)
        val imports = SourceImports(lock)
        val progress = ImportProgress()
        var started = false
        val add = async { imports.run(progress) { started = true } }
        withTimeout(2_000) { progress.stage.first { it == "Sırada bekleniyor…" } }
        assertTrue(imports.userWaiting)
        add.cancelAndJoin()
        assertFalse(started)
        assertFalse(imports.userWaiting)
        assertTrue(lock.isLocked)
        lock.unlock()
    }

    @Test fun queuedAddProceedsWhenOtherJobReleasesLock() = runBlocking {
        val lock = Mutex(locked = true)
        val imports = SourceImports(lock)
        val progress = ImportProgress()
        val add = async { imports.run(progress) { progress.report("Hesap doğrulanıyor…"); 7 } }
        withTimeout(2_000) { progress.stage.first { it == "Sırada bekleniyor…" } }
        lock.unlock()
        assertEquals(7, withTimeout(2_000) { add.await() })
        assertFalse(imports.userWaiting)
        assertFalse(lock.isLocked)
        assertEquals("Hesap doğrulanıyor…", progress.stage.value)
    }

    @Test fun wholeAddDeadlineIncludesLockWait() = runBlocking {
        val lock = Mutex(locked = true)
        val imports = SourceImports(lock)
        var started = false
        val error = runCatching { imports.run(timeoutMillis = 100) { started = true } }.exceptionOrNull()
        assertTrue(error is SourceFailure)
        assertTrue(error!!.message!!.contains("15 dakika"))
        assertFalse(started)
        assertFalse(imports.userWaiting)
        lock.unlock()
    }

    @Test fun cancelledAddCleansPublishedSourceBeforeReleasingLock() = runBlocking {
        val lock = Mutex()
        val published = CompletableDeferred<Unit>()
        var sourceExists = false
        var cleaned = false
        val add = async {
            SourceImports(lock).run(onFailure = {
                assertTrue(lock.isLocked)
                sourceExists = false
                cleaned = true
            }) {
                sourceExists = true
                published.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(2_000) { published.await() }
        assertTrue(sourceExists)
        add.cancelAndJoin()
        assertTrue(cleaned)
        assertFalse(sourceExists)
        assertFalse(lock.isLocked)
    }

    @Test fun wholeAddDeadlineClosesBlockedCallAndCleansBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 40, TimeUnit.MILLISECONDS))
            val lock = Mutex()
            val error = runCatching {
                SourceImports(lock).run(timeoutMillis = 200) {
                    Transport.download(server.url("/").toString(), true, cache.root,
                        calls = currentCoroutineContext()[ImportCalls]).close()
                }
            }.exceptionOrNull()
            assertTrue(error is SourceFailure)
            assertTrue(error!!.message!!.contains("15 dakika"))
            assertTrue(cache.root.listFiles()!!.isEmpty())
            assertFalse(lock.isLocked)
        }
    }

    @Test fun cancellingSnapshotClosesCurrentCallAndDeletesEarlierBodies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1}}"""))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 40, TimeUnit.MILLISECONDS))
            val started = CompletableDeferred<Unit>()
            val lock = Mutex()
            val add = async {
                SourceImports(lock).run {
                    val job = currentCoroutineContext()
                    var liveUpdates = 0
                    Xtream(SourceSpec(server.url("/").toString(), true, "u", "p"), job[ImportCalls])
                        .prepare(cache.root, FetchState(), progress = {
                            if (it.startsWith("Canlı") && ++liveUpdates == 3) started.complete(Unit)
                        }, checkActive = { job.ensureActive() }).close()
                }
            }
            withTimeout(2_000) { started.await() }
            assertTrue(cache.root.listFiles()!!.isNotEmpty())
            withTimeout(2_000) { add.cancelAndJoin() }
            assertTrue(cache.root.listFiles()!!.isEmpty())
            assertFalse(lock.isLocked)
        }
    }

    @Test fun progressIncludesEveryDownloadStageAndCanBeReset() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1}}"""))
        repeat(6) { server.enqueue(MockResponse().setBody("[]")) }
        val progress = ImportProgress()
        val stages = mutableListOf<String>()
        Xtream(SourceSpec(server.url("/").toString(), true, "u", "p"))
            .prepare(cache.root, FetchState(), progress = { progress.report(it); stages += it }).close()
        assertEquals("Hesap doğrulanıyor…", stages.first())
        for (name in listOf("Canlı kanallar", "Filmler", "Diziler"))
            assertTrue(stages.any { it.startsWith("$name indiriliyor · ") && it.endsWith(" MB") })
        progress.resolved("https://provider.invalid/prefix")
        assertEquals("https://provider.invalid/prefix", progress.address.value)
        progress.reset()
        assertEquals("", progress.stage.value)
        assertEquals("", progress.address.value)
        assertEquals("39.682", importCount(39682))
    }
}
