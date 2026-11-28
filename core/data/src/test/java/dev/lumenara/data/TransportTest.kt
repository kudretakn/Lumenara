package dev.lumenara.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TransportTest {
    @Test fun httpsOnlyClientRejectsHttpBeforeRequest() {
        val error = runCatching { Transport.read("http://127.0.0.1:1/private", false) { _, _ -> Unit } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(error!!.message.orEmpty().contains("private"))
    }
    @Test fun httpEnabledClientCanReadHttpSource() = MockWebServer().use { s ->
        s.enqueue(MockResponse().setBody("ok")); assertEquals("ok", Transport.read(s.url("/").toString(), true) { i, _ -> i.reader().readText() })
    }
    @Test fun httpsOnlyClientRejectsHttpRedirect() {
        val tls = Wp21TlsFixture()
        MockWebServer().use { http ->
            MockWebServer().use { https ->
                https.useHttps(tls.serverSocketFactory, false)
                http.start()
                https.start()
                val destination = http.url("/private?token=fixture")
                // Follow a same-scheme hop before encountering the actual downgrade.
                https.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/next"))
                https.enqueue(MockResponse().setResponseCode(301).addHeader("Location", destination))
                http.enqueue(MockResponse().setBody("allowed"))
                tls.client(false).newCall(Request.Builder().url(https.url("/start")).build())
                    .execute().use { response ->
                        assertEquals(301, response.code)
                        assertEquals("/next", response.request.url.encodedPath)
                        assertTrue(response.request.url.isHttps)
                        assertEquals(destination.toString(), response.header("Location"))
                        assertNotNull("TLS must succeed before checking redirect policy", response.handshake)
                    }
                assertEquals("/start", https.takeRequest(1, TimeUnit.SECONDS)?.path)
                assertEquals("/next", https.takeRequest(1, TimeUnit.SECONDS)?.path)
                assertEquals(2, https.requestCount)
                assertEquals(0, http.requestCount)
                // A positive control proves the destination works with source HTTP opt-in.
                https.enqueue(MockResponse().setResponseCode(301).addHeader("Location", destination))
                tls.client(true).newCall(Request.Builder().url(https.url("/opted-in")).build())
                    .execute().use { response ->
                        assertEquals(200, response.code)
                        assertEquals(destination, response.request.url)
                        assertEquals("allowed", response.body?.string())
                    }
                assertEquals("/opted-in", https.takeRequest(1, TimeUnit.SECONDS)?.path)
                assertEquals("/private?token=fixture", http.takeRequest(1, TimeUnit.SECONDS)?.path)
                assertEquals(1, http.requestCount)
            }
        }
    }
    @Test fun redirectsWithoutLocationFailWithSafeMessage() = MockWebServer().use { s ->
        s.enqueue(MockResponse().setResponseCode(301)); val e = runCatching { Transport.read(s.url("/").toString(), true) { _, _ -> Unit } }.exceptionOrNull()
        assertTrue(e is SourceFailure); assertFalse(e!!.message.orEmpty().contains("127.0.0.1"))
    }
    @Test fun redirectsWithoutLocationAreReportedSafelyForAllSupportedCodes() {
        listOf(300,301,302,307,308).forEach { code -> MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(code)); val e = runCatching { Transport.read(s.url("/user?password=secret").toString(), true) { _, _ -> Unit } }.exceptionOrNull()
            assertTrue(e is SourceFailure); assertFalse(e!!.message.orEmpty().contains("secret"))
        } }
    }
    @Test fun nonSuccessfulResponseDoesNotExposeAddress() = MockWebServer().use { s ->
        s.enqueue(MockResponse().setResponseCode(503)); val e = runCatching { Transport.read(s.url("/credential").toString(), true) { _, _ -> Unit } }.exceptionOrNull()
        assertTrue(e is SourceFailure); assertFalse(e!!.message.orEmpty().contains("credential"))
    }
    @Test fun successfulRedirectIsFollowedForHttpEnabledSource() = MockWebServer().use { s ->
        s.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final")); s.enqueue(MockResponse().setBody("done"))
        assertEquals("done", Transport.read(s.url("/").toString(), true) { i, _ -> i.reader().readText() })
    }
}
