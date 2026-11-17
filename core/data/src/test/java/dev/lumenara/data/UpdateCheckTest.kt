package dev.lumenara.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckTest {
    private val digest = "a".repeat(64)
    private val missingDigest = "Sürüm bulundu ama doğrulama özeti alınamadı; sonra tekrar deneyin."

    private class MemoryStore : DataStore<StoredPreferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (StoredPreferences) -> StoredPreferences): StoredPreferences =
            mutex.withLock { transform(data.value).toPreferences().also { data.value = it } }
    }

    private fun preferences(store: MemoryStore = MemoryStore()) = Preferences(store, { it }, { it })
    private fun checker(server: MockWebServer, now: Long = 1_700_000_000_500L,
        client: OkHttpClient = Transport.client(true)) =
        UpdateChecker(server.url("/"), server.url("/"), client) { now }

    private fun release(tag: String = "v2.0", hash: String = digest) = """
        {"tag_name":"$tag","body":"notes","assets":[{"name":"app.apk",
        "browser_download_url":"https://github.com/o/r/releases/download/$tag/app.apk",
        "digest":"sha256:$hash"}]}
    """.trimIndent()

    private fun asset(name: String = "app.apk", hash: String = digest) = """
        <li class="Box-row"><div><a href="/o/r/releases/download/v2.0/$name">$name</a></div>
        <div><span>sha256:$hash</span><clipboard-copy value="sha256:$hash"></clipboard-copy></div></li>
    """.trimIndent()

    private fun MockWebServer.noFallback() {
        enqueue(MockResponse().setResponseCode(404)) // latest redirect
        enqueue(MockResponse().setResponseCode(404)) // Atom
    }

    @Test fun exhaustedLimitRoundsResetUpToMinutes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403)
                .addHeader("x-ratelimit-remaining", "0")
                .addHeader("x-ratelimit-reset", "1700000182"))
            server.noFallback()
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertTrue(error is SourceFailure)
            assertEquals("GitHub istek sınırına ulaşıldı, 4 dakika sonra tekrar deneyin.", error!!.message)
        }
    }

    @Test fun retryAfterIsRespectedForSecondaryLimits() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "121")
                .addHeader("x-ratelimit-remaining", "52").addHeader("x-ratelimit-reset", "1700003600"))
            server.noFallback()
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals("GitHub istek sınırına ulaşıldı, 3 dakika sonra tekrar deneyin.", error!!.message)
        }
    }

    @Test fun forbiddenApiFallsBackToLatestRedirectAndAssetDigest() = runBlocking {
        MockWebServer().use { server ->
            val prefs = preferences()
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/o/r/releases/tag/v2.0"))
            server.enqueue(MockResponse().setBody(asset()))
            val update = checker(server).check("o/r", "1.0", prefs)
            assertEquals("v2.0", update!!.version)
            assertEquals(digest, update.sha256)
            assertEquals("https://github.com/o/r/releases/download/v2.0/app.apk", update.url)
            assertEquals("/repos/o/r/releases/latest", server.takeRequest().path)
            assertEquals("/o/r/releases/latest", server.takeRequest().path)
            assertEquals("/o/r/releases/expanded_assets/v2.0", server.takeRequest().path)
            assertEquals("v2.0", prefs.updateState("o/r").tag)
            assertEquals("", prefs.updateState("o/r").etag)
        }
    }

    @Test fun ioFailureFallsBackToAtomWhenLatestRedirectIsUnavailable() = runBlocking {
        MockWebServer().use { server ->
            val client = Transport.client(true).newBuilder().addInterceptor {
                if (it.request().url.encodedPath.startsWith("/repos/")) throw IOException("offline API")
                it.proceed(it.request())
            }.build()
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""
                <feed xmlns="http://www.w3.org/2005/Atom"><entry>
                <link rel="alternate" href="${server.url("/o/r/releases/tag/v2.0")}"/>
                </entry></feed>
            """.trimIndent()))
            server.enqueue(MockResponse().setBody(asset()))
            val update = checker(server, client = client).check("o/r", "1.0", preferences())
            assertEquals("v2.0", update!!.version)
            assertEquals(digest, update.sha256)
            assertEquals("/o/r/releases/latest", server.takeRequest().path)
            assertEquals("/o/r/releases.atom", server.takeRequest().path)
        }
    }

    @Test fun apiRequestContainsGitHubHeadersAndAppVersion() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release()))
            checker(server).check("o/r", "0.5.0", preferences())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("Lumenara/0.5.0", request.getHeader("User-Agent"))
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        }
    }

    @Test fun downloadForbiddenHasApkSpecificHttpMessage() {
        MockWebServer().use { server ->
            val file = File.createTempFile("update-test-", ".apk")
            try {
                server.enqueue(MockResponse().setResponseCode(403))
                val error = runCatching {
                    Updates.downloadApk(server.url("/app.apk").toString(), file, Transport.client(true))
                }.exceptionOrNull()
                assertTrue(error is SourceFailure)
                assertEquals("APK indirilemedi (HTTP 403)", error!!.message)
                assertEquals(0L, file.length())
            } finally { file.delete() }
        }
    }

    @Test fun storedEtagIsSentAndNotModifiedReturnsUnchanged() = runBlocking {
        MockWebServer().use { server ->
            val store = MemoryStore()
            server.enqueue(MockResponse().setBody(release()).addHeader("ETag", "\"release-2\""))
            val first = checker(server).check("o/r", "1.0", preferences(store))
            assertNotNull(first)
            server.enqueue(MockResponse().setResponseCode(304))
            assertEquals(first, checker(server).check("o/r", "1.0", preferences(store)))
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            assertEquals("\"release-2\"", server.takeRequest().getHeader("If-None-Match"))
            assertEquals("v2.0", preferences(store).updateState("o/r").tag)
            assertEquals("\"release-2\"", preferences(store).updateState("o/r").etag)
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(304))
            assertNull(checker(server).check("o/r", "2.0", preferences(store)))
        }
    }

    @Test fun notModifiedForInstalledReleaseStillMeansNoUpdate() = runBlocking {
        MockWebServer().use { server ->
            val prefs = preferences()
            server.enqueue(MockResponse().setBody(release()).addHeader("ETag", "\"v2\""))
            assertNull(checker(server).check("o/r", "2.0", prefs))
            server.enqueue(MockResponse().setResponseCode(304))
            assertNull(checker(server).check("o/r", "2.0", prefs))
            server.takeRequest()
            assertEquals("\"v2\"", server.takeRequest().getHeader("If-None-Match"))
        }
    }

    @Test fun failedCheckKeepsLastSuccessfulTagAndEtag() = runBlocking {
        MockWebServer().use { server ->
            val prefs = preferences()
            server.enqueue(MockResponse().setBody(release()).addHeader("ETag", "\"v2\""))
            checker(server).check("o/r", "1.0", prefs)
            server.enqueue(MockResponse().setResponseCode(500))
            server.noFallback()
            assertTrue(runCatching { checker(server).check("o/r", "1.0", prefs) }.isFailure)
            assertEquals("v2.0", prefs.updateState("o/r").tag)
            assertEquals("\"v2\"", prefs.updateState("o/r").etag)
            assertEquals("", prefs.updateState("another/repo").etag)
        }
    }

    @Test fun missingDigestRefusesUpdateAndDoesNotAcknowledgeRelease() = runBlocking {
        MockWebServer().use { server ->
            val prefs = preferences()
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/o/r/releases/tag/v2.0"))
            server.enqueue(MockResponse().setBody(asset(hash = "")))
            val error = runCatching { checker(server).check("o/r", "1.0", prefs) }.exceptionOrNull()
            assertTrue(error is SourceFailure)
            assertEquals(missingDigest, error!!.message)
            assertEquals("", prefs.updateState("o/r").tag)
        }
    }

    @Test fun digestOfAnotherAssetIsNeverUsedForApk() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/o/r/releases/tag/v2.0"))
            server.enqueue(MockResponse().setBody(asset(hash = "") + asset(name = "other.zip")))
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals(missingDigest, error!!.message)
        }
    }

    @Test fun apiWithoutDigestCanUseWebDigestForTheSameApk() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release(hash = "")))
            server.enqueue(MockResponse().setBody(asset()))
            val update = checker(server).check("o/r", "1.0", preferences())
            assertEquals(digest, update!!.sha256)
            assertEquals("/repos/o/r/releases/latest", server.takeRequest().path)
            assertEquals("/o/r/releases/expanded_assets/v2.0", server.takeRequest().path)
        }
    }

    @Test fun apiAndWebWithoutDigestRefuseUpdate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release(hash = "")))
            server.enqueue(MockResponse().setBody(asset(hash = "")))
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals(missingDigest, error!!.message)
        }
    }

    @Test fun forbiddenWithoutRateLimitHeadersKeepsUpdateSpecificMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.noFallback()
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals("Sürüm denetimi başarısız (HTTP 403)", error!!.message)
        }
    }

    @Test fun productionCheckerRejectsHttpBeforeRequest() = runBlocking {
        MockWebServer().use { server ->
            val error = runCatching {
                UpdateChecker(server.url("/"), server.url("/"))
                    .check("o/r", "1.0", preferences())
            }.exceptionOrNull()
            assertTrue(error is SourceFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun fallbackDoesNotTrustRedirectToAnotherHost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.com/o/r/releases/tag/v2.0"))
            server.enqueue(MockResponse().setResponseCode(404))
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals("Sürüm denetimi başarısız (HTTP 403)", error!!.message)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun automaticChecksSurviveRecreationAndAreLimitedToOncePerHour() = runBlocking {
        MockWebServer().use { server ->
            val store = MemoryStore()
            val start = 1_700_000_000_000L
            server.enqueue(MockResponse().setBody(release()).addHeader("ETag", "\"v2\""))
            val first = checker(server, start).check("o/r", "1.0", preferences(store), automatic = true)
            assertEquals(first, checker(server, start + 3_599_999).check("o/r", "1.0", preferences(store), automatic = true))
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(304))
            checker(server, start + 3_600_000).check("o/r", "1.0", preferences(store), automatic = true)
            assertEquals(2, server.requestCount)
            // Settings can still explicitly check during the automatic cooldown.
            server.enqueue(MockResponse().setResponseCode(304))
            checker(server, start + 3_600_001).check("o/r", "1.0", preferences(store))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun failedAutomaticChecksAreAlsoThrottled() = runBlocking {
        MockWebServer().use { server ->
            val prefs = preferences()
            server.enqueue(MockResponse().setResponseCode(500))
            server.noFallback()
            val error = runCatching { checker(server).check("o/r", "1.0", prefs, automatic = true) }.exceptionOrNull()
            assertEquals("Sürüm denetimi başarısız (HTTP 500)", error!!.message)
            assertNull(checker(server).check("o/r", "1.0", prefs, automatic = true))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun invalidRepositoryIsRejectedBeforeAnyRequest() = runBlocking {
        MockWebServer().use { server ->
            val error = runCatching { checker(server).check("o/../r", "1.0", preferences()) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun transientApiFailuresFallBackIncludingInvalidJsonAndRateLimits() = runBlocking {
        for (code in listOf(429, 500, 503, 200)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("invalid JSON"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/o/r/releases/tag/v2.0"))
            server.enqueue(MockResponse().setBody(asset()))
            assertEquals(digest, checker(server).check("o/r", "1.0", preferences())!!.sha256)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun invalidJsonWithNoFallbackReportsUnexpectedResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("not JSON"))
            server.noFallback()
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals("Sunucudan beklenmeyen bir yanıt geldi.", error!!.message)
        }
    }

    @Test fun missingRepositoryDoesNotFallBack() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals("Sürüm denetimi başarısız (HTTP 404)", error!!.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun fallbackEqualOrOlderThanInstalledReturnsNull() = runBlocking {
        for (tag in listOf("v1.0", "v0.9")) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/o/r/releases/tag/$tag"))
            assertNull(checker(server).check("o/r", "1.0", preferences()))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun stableLatestRedirectTakesPriorityOverAtomPrerelease() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/o/r/releases/tag/v2.0"))
            // A feed containing a pre-release must never be requested after a valid latest redirect.
            server.enqueue(MockResponse().setBody(asset()))
            server.enqueue(MockResponse().setBody("<feed><entry><link href=\"/o/r/releases/tag/v3.0-rc1\"/></entry></feed>"))
            assertEquals("v2.0", checker(server).check("o/r", "1.0", preferences())!!.version)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun atomRejectsPrereleaseTraversalAndForeignLinks() = runBlocking {
        for (link in listOf("/o/r/releases/tag/v3.0-rc1", "/o/r/releases/tag/../v2.0",
            "https://example.com/o/r/releases/tag/v2.0", "/other/repo/releases/tag/v2.0")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(403))
                server.enqueue(MockResponse().setResponseCode(503))
                server.enqueue(MockResponse().setBody("<feed><entry><link href=\"$link\"/></entry></feed>"))
                val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
                assertTrue(error is SourceFailure)
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun atomTagRequiresAnExistingExpandedAssetsPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("<feed><entry><link href=\"/o/r/releases/tag/v2.0\"/></entry></feed>"))
            server.enqueue(MockResponse().setResponseCode(404))
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals(missingDigest, error!!.message)
        }
    }

    @Test fun expandedAssetsRefusesTwoApksEvenWithOneUniversal() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/o/r/releases/tag/v2.0"))
            server.enqueue(MockResponse().setBody(asset("universal.apk") + asset("arm.apk")))
            val error = runCatching { checker(server).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals(missingDigest, error!!.message)
        }
    }

    @Test fun mixedCaseRepositoryUsesCanonicalAssetPath() = runBlocking {
        for (apiAvailable in listOf(true, false)) MockWebServer().use { server ->
            if (apiAvailable) server.enqueue(MockResponse().setBody(release(hash = "")
                .replace("github.com/o/r/", "github.com/O/R/")))
            else {
                server.enqueue(MockResponse().setResponseCode(403))
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/O/R/releases/tag/v2.0"))
            }
            server.enqueue(MockResponse().setBody(asset().replace("/o/r/", "/O/r/")))
            val update = checker(server).check("o/R", "1.0", preferences())!!
            assertEquals(digest, update.sha256)
            assertEquals("https://github.com/O/r/releases/download/v2.0/app.apk", update.url)
        }
    }

    @Test fun futureCheckedAtDoesNotBlockAutomaticCheck() = runBlocking {
        MockWebServer().use { server ->
            val prefs = preferences()
            assertTrue(prefs.beginUpdateCheck("o/r", true, 1_800_000_000_000L))
            server.enqueue(MockResponse().setBody(release()))
            assertNotNull(checker(server).check("o/r", "1.0", prefs, automatic = true))
            assertEquals(1, server.requestCount)
            assertEquals(1_700_000_000_500L, prefs.updateState("o/r").checkedAt)
        }
    }

    @Test fun corruptCachedReleaseIsACacheMiss() = runBlocking {
        MockWebServer().use { server ->
            val prefs = preferences()
            prefs.beginUpdateCheck("o/r", true, 1_700_000_000_500L)
            prefs.setUpdateRelease("o/r", "v2.0", "etag", "{broken")
            assertNull(checker(server).check("o/r", "1.0", prefs, automatic = true))
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(304))
            assertNull(checker(server).check("o/r", "1.0", prefs))
            // Corrupt outer state also decodes to an empty state and allows a fresh check.
            prefs.set("update_o/r", "{broken")
            server.enqueue(MockResponse().setBody(release()))
            assertNotNull(checker(server).check("o/r", "1.0", prefs, automatic = true))
        }
    }

    @Test fun cacheRetainsOnlyReleaseAndAssetFieldsNeededByUpdater() = runBlocking {
        MockWebServer().use { server ->
            val prefs = preferences()
            val json = org.json.JSONObject(release()).put("unneeded", "discard")
            json.getJSONArray("assets").getJSONObject(0).put("uploader", org.json.JSONObject().put("login", "discard"))
            server.enqueue(MockResponse().setBody(json.toString()).addHeader("ETag", "etag"))
            checker(server).check("o/r", "1.0", prefs)
            val state = prefs.updateState("o/r")
            val cached = org.json.JSONObject(state.release)
            assertEquals(setOf("tag_name", "body", "assets"), cached.keys().asSequence().toSet())
            assertEquals(setOf("name", "browser_download_url", "digest"),
                cached.getJSONArray("assets").getJSONObject(0).keys().asSequence().toSet())
            assertEquals("etag", state.etag)
            assertEquals("v2.0", state.tag)
            assertEquals(1_700_000_000_500L, state.checkedAt)
        }
    }

    @Test fun rateLimitUsesServerDateWhenDeviceClockIsWrong() = runBlocking {
        for (date in listOf("Tue, 14 Nov 2023 22:13:20 GMT", "invalid")) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).addHeader("Date", date)
                .addHeader("x-ratelimit-remaining", "0").addHeader("x-ratelimit-reset", "1700000182"))
            server.noFallback()
            val deviceNow = if (date == "invalid") 1_700_000_000_500L else 1_800_000_000_000L
            val error = runCatching { checker(server, deviceNow).check("o/r", "1.0", preferences()) }.exceptionOrNull()
            assertEquals("GitHub istek sınırına ulaşıldı, 4 dakika sonra tekrar deneyin.", error!!.message)
        }
    }

}
