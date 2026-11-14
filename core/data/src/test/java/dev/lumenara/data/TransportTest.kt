package dev.lumenara.data

import java.io.IOException
import java.util.concurrent.TimeUnit
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
    @Test fun httpsOnlyClientRejectsHttpRedirect() = MockWebServer().use { s ->
        s.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://example.com/private"))
        val e = runCatching { Transport.read(s.url("/").toString().replace("http://", "https://"), false) { _, _ -> Unit } }.exceptionOrNull()
        assertNotNull(e)
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
