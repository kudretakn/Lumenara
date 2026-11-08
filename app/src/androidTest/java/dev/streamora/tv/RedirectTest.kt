package dev.streamora.tv

import dev.streamora.catalog.Playlist
import dev.streamora.data.SourceFailure
import dev.streamora.data.Transport
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class RedirectTest {
    private val certificate =
        HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls =
        HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    private fun client(allowHttp: Boolean) =
        Transport.client(allowHttp)
            .newBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .build()

    @Test
    fun followsHttp301ToHttpsWhenCompatibilityIsEnabled() {
        MockWebServer().use { http ->
            MockWebServer().use { https ->
                https.useHttps(serverTls.sslSocketFactory(), false)
                http.start()
                https.start()
                val destination = https.url("/catalog?token=fixture")
                http.enqueue(MockResponse().setResponseCode(301).setHeader("Location", destination))
                https.enqueue(MockResponse().setBody("#EXTM3U"))
                client(true)
                    .newCall(Request.Builder().url(http.url("/old")).build())
                    .execute()
                    .use {
                        assertEquals(200, it.code)
                        assertEquals(destination, it.request.url)
                        assertEquals("#EXTM3U", it.body?.string())
                    }
                assertEquals("/catalog?token=fixture", https.takeRequest(2, TimeUnit.SECONDS)?.path)
            }
        }
    }

    @Test
    fun followsHttpsToHttpOnlyForAnOptedInSource() {
        MockWebServer().use { http ->
            MockWebServer().use { https ->
                https.useHttps(serverTls.sslSocketFactory(), false)
                http.start()
                https.start()
                https.enqueue(
                    MockResponse().setResponseCode(301).setHeader("Location", http.url("/catalog"))
                )
                http.enqueue(MockResponse().setBody("allowed"))
                client(true)
                    .newCall(Request.Builder().url(https.url("/old")).build())
                    .execute()
                    .use {
                        assertEquals("allowed", it.body?.string())
                    }
            }
        }
    }

    @Test
    fun httpsOnlyClientStopsADowngradeAfterAnHttpsRedirect() {
        MockWebServer().use { http ->
            MockWebServer().use { https ->
                https.useHttps(serverTls.sslSocketFactory(), false)
                http.start()
                https.start()
                https.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
                https.enqueue(
                    MockResponse()
                        .setResponseCode(301)
                        .setHeader("Location", http.url("/private?token=fixture"))
                )
                client(false)
                    .newCall(Request.Builder().url(https.url("/start")).build())
                    .execute()
                    .use {
                        assertEquals(301, it.code)
                        assertTrue(it.request.url.isHttps)
                        assertEquals("/next", it.request.url.encodedPath)
                    }
                assertEquals(2, https.requestCount)
                assertEquals(0, http.requestCount)
            }
        }
    }

    @Test
    fun relativePlaylistEntriesUseTheFinalRedirectedAddress() {
        MockWebServer().use { server ->
            server.start()
            for ((code, location) in
                listOf(301 to "/next", 307 to "/again", 308 to "/moved/list.m3u")) server.enqueue(
                MockResponse().setResponseCode(code).setHeader("Location", location)
            )
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,Test\nmedia/channel.ts"))
            var media = ""
            Transport.read(server.url("/start").toString(), true) { input, finalUrl ->
                assertEquals(server.url("/moved/list.m3u").toString(), finalUrl)
                Playlist.read(input.reader(), URI(finalUrl), true) { media = it.url }
            }
            assertEquals(server.url("/moved/media/channel.ts").toString(), media)
        }
    }

    @Test
    fun incompleteRedirectHasAnActionableMessageWithoutEchoingCredentials() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(301))
            val failure = runCatching {
                Transport.read(server.url("/list?password=fixture").toString(), true) { _, _ -> }
            }.exceptionOrNull()
            assertTrue(failure is SourceFailure)
            assertTrue(failure?.message.orEmpty().contains("yönlendirme adresi"))
            assertFalse(failure?.message.orEmpty().contains("fixture"))
        }
    }
}
