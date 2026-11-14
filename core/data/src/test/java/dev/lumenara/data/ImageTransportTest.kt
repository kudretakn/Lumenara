package dev.lumenara.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ImageTransportTest {
    @Test fun httpRequiresExplicitSourcePermission() {
        val http = "http://images.example/logo.png".toHttpUrl()
        val https = "https://images.example/logo.png".toHttpUrl()
        assertFalse(Transport.imageUrlAllowed(http, false))
        assertTrue(Transport.imageUrlAllowed(http, true))
        assertTrue(Transport.imageUrlAllowed(https, false))
        assertTrue(Transport.imageUrlAllowed(https, true))
        for (allow in listOf(false, true)) {
            assertFalse(Transport.imageUrlAllowed("https://user:secret@images.example/a".toHttpUrl(), allow))
            assertFalse(Transport.imageUrlAllowed("http://user:secret@images.example/a".toHttpUrl(), allow))
        }
    }

    @Test fun defaultRequestNeverReachesHttpServer() {
        MockWebServer().use { server ->
            server.start()
            val request = Request.Builder().url(server.url("/logo")).build()
            try {
                Transport.imageClient().newCall(request).execute().close()
                fail("HTTP requires source permission")
            } catch (_: IOException) { }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun allowedRedirectsDoNotCarryCredentialsOrPermissionHeader() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next")
                .setHeader("Set-Cookie", "session=secret; Path=/"))
            server.enqueue(MockResponse().setBody("image"))
            val request = Request.Builder().url(server.url("/logo"))
                .header(Transport.IMAGE_HTTP_HEADER, "true")
                .header("Cookie", "session=secret").header("Authorization", "Bearer secret")
                .header("Proxy-Authorization", "Basic secret").build()
            Transport.imageClient().newCall(request).execute().use { assertTrue(it.isSuccessful) }
            repeat(2) {
                val received = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertNull(received.getHeader(Transport.IMAGE_HTTP_HEADER))
                assertNull(received.getHeader("Cookie"))
                assertNull(received.getHeader("Authorization"))
                assertNull(received.getHeader("Proxy-Authorization"))
            }
        }
    }
}
