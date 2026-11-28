package dev.lumenara.data

import dev.lumenara.catalog.Listing
import dev.lumenara.catalog.Playlist
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Wp16TransportTest {
    @get:Rule val cache = TemporaryFolder()
    private val secret = "wp16-password-do-not-persist"
    private val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="one" group-title="Haber",Kanal
        https://provider.invalid/live/user/$secret/1.ts
        #EXTINF:-1 group-title="Filmler",Film
        relative.mp4
    """.trimIndent()

    @Test fun encryptedDownloadMatchesPlainFixtureParserAndDigest() = MockWebServer().use { server ->
        val url = server.url("/prefix/list").toString()
        server.enqueue(MockResponse().setBody(playlist))
        val expected = mutableListOf<Listing>()
        Playlist.read(playlist.reader(), URI(url), true, expected::add)
        Transport.download(url, true, cache.root, spool = EncryptedSpool.ephemeral()).use { body ->
            val actual = mutableListOf<Listing>()
            body.read { input, finalUrl -> Playlist.read(input.reader(), URI(finalUrl), true, actual::add) }
            assertEquals(expected, actual)
            val digest = MessageDigest.getInstance("SHA-256").digest(playlist.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(digest, body.state.digest)
            val disk = cache.root.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains(secret))
            assertFalse(disk.contains("https://provider.invalid"))
        }
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }

    @Test fun digestAnd304StillSkipImportAnd304DoesNotCreateASpool() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/list").toString()
            server.enqueue(MockResponse().setBody(playlist).addHeader("ETag", "v1"))
            server.enqueue(MockResponse().setBody(playlist))
            server.enqueue(MockResponse().setResponseCode(304))
            val state = Transport.download(url, true, cache.root).use { it.state }
            Transport.download(url, true, cache.root, state).use {
                assertFalse(it.importIfChanged { fail("same digest must skip import") })
            }
            val noWrite = EncryptedSpool({ error("304 must not wrap a spool key") }, { error("no read") })
            Transport.download(url, true, cache.root, state, spool = noWrite).use {
                assertEquals(state, it.state)
                assertFalse(it.importIfChanged { fail("304 must skip import") })
            }
            server.takeRequest(); server.takeRequest()
            assertEquals("v1", server.takeRequest().getHeader("If-None-Match"))
            assertTrue(cache.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun byteLimitAndCancellationRemoveEncryptedBodies() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(playlist))
        assertTrue(runCatching {
            Transport.download(server.url("/").toString(), true, cache.root, limit = 2)
        }.exceptionOrNull() is SourceFailure)
        assertTrue(cache.root.listFiles()!!.isEmpty())
        server.enqueue(MockResponse().setBody(playlist))
        val cancelled = CancellationException(secret)
        var checks = 0
        val error = runCatching {
            Transport.download(server.url("/").toString(), true, cache.root, checkActive = {
                if (++checks == 3) throw cancelled
            })
        }.exceptionOrNull()
        assertSame(cancelled, error)
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }

    @Test fun parserCannotAcceptAPrefixOfATruncatedSpool() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(playlist))
        Transport.download(server.url("/").toString(), true, cache.root).use { body ->
            val file = cache.root.listFiles()!!.single()
            file.writeBytes(file.readBytes().dropLast(20).toByteArray())
            val error = runCatching { body.read { input, _ -> input.read(); "accepted prefix" } }.exceptionOrNull()
            assertTrue(error is SourceFailure)
            assertEquals("SPOOL_READ", error!!.message!!.split('|')[1])
        }
    }

    @Test fun invalidUrlsParserFailuresAndImportFailuresHaveOnlySafeDiagnosticFields() = runBlocking {
        val invalid = runCatching { Transport.download("invalid:$secret", true, cache.root) }.exceptionOrNull()
        assertEquals("DATA_FAILED|TRANSPORT|IllegalArgumentException", invalid!!.message)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("body"))
            val error = runCatching {
                Transport.read(server.url("/$secret").toString(), true) { _, _ -> throw IOException(secret) }
            }.exceptionOrNull()
            assertEquals("DATA_FAILED|TRANSPORT|IOException", error!!.message)
            assertNull(error.cause)
        }
        val error = runCatching {
            SourceImports(Mutex()).run<Unit> { throw IllegalStateException("https://private.invalid/$secret") }
        }.exceptionOrNull()
        assertEquals("DATA_FAILED|IMPORT|IllegalStateException", error!!.message)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
    }
}
