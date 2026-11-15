package dev.lumenara.data

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConditionalRefreshTest {
    @get:Rule val cache = TemporaryFolder()

    @Test fun validatorsSurvivePersistenceAnd304SkipsImport() = runTest {
        MockWebServer().use { server ->
            val modified = "Sat, 14 Nov 2026 10:00:00 GMT"
            server.enqueue(MockResponse().setBody("catalog").addHeader("ETag", "\"v1\"")
                .addHeader("Last-Modified", modified))
            server.enqueue(MockResponse().setResponseCode(304))
            var writes = 0
            val url = server.url("/list.m3u").toString()
            val saved = Transport.download(url, true, cache.root).use { body ->
                assertTrue(body.importIfChanged { writes++ })
                body.state.encode()
            }
            // Preferences encrypts this exact serialization; reconstruct as after a process restart.
            val previous = FetchState.decode(saved)
            assertEquals("\"v1\"", previous.etag)
            assertEquals(modified, previous.modified)
            Transport.download(url, true, cache.root, previous).use { body ->
                assertFalse(body.importIfChanged { fail("304 must not enter the import transaction") })
                assertEquals(previous, body.state)
            }
            assertEquals(1, writes)
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            val conditional = server.takeRequest()
            assertEquals("\"v1\"", conditional.getHeader("If-None-Match"))
            assertEquals(modified, conditional.getHeader("If-Modified-Since"))
            assertTrue(cache.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun sameDigestSkipsWritesWithoutValidatorsAndChangedBodyImports() = runTest {
        MockWebServer().use { server ->
            listOf("catalog", "catalog", "updated").forEach { server.enqueue(MockResponse().setBody(it)) }
            var state = FetchState()
            var writes = 0
            repeat(3) { index ->
                Transport.download(server.url("/").toString(), true, cache.root, state).use { body ->
                    assertEquals(index != 1, body.importIfChanged { writes++ })
                    state = body.state
                }
            }
            assertEquals(2, writes)
            assertTrue(cache.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun gzipIsDecodedTransparentlyAndComparedWithPlainBody() = runTest {
        MockWebServer().use { server ->
            val compressed = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write("catalog".toByteArray()) }
            }.toByteArray()
            server.enqueue(MockResponse().setBody(Buffer().write(compressed)).addHeader("Content-Encoding", "gzip"))
            server.enqueue(MockResponse().setBody("catalog"))
            val url = server.url("/").toString()
            val previous = Transport.download(url, true, cache.root).use { body ->
                assertEquals("catalog", body.read { input, _ -> input.reader().readText() })
                body.state
            }
            Transport.download(url, true, cache.root, previous).use { body ->
                assertFalse(body.importIfChanged { fail("Decoded bytes are identical") })
            }
            assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
        }
    }

    @Test fun failedImportDoesNotAcknowledgeNewEtag() = runTest {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("bad").addHeader("ETag", "new")) }
            val previous = FetchState(etag = "old", digest = "old", finalUrl = server.url("/").toString())
            var persisted = previous
            try {
                Transport.download(server.url("/").toString(), true, cache.root, persisted).use { body ->
                    body.importIfChanged { throw SourceFailure("Invalid catalog") }
                    persisted = body.state
                }
                fail("Import must fail")
            } catch (_: SourceFailure) { }
            Transport.download(server.url("/").toString(), true, cache.root, persisted).close()
            server.takeRequest()
            assertEquals("old", server.takeRequest().getHeader("If-None-Match"))
            assertTrue(cache.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun oversizedDownloadDeletesTemporaryBody() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("too big"))
        val error = runCatching {
            Transport.download(server.url("/").toString(), true, cache.root, limit = 2)
        }.exceptionOrNull()
        assertTrue(error is SourceFailure)
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }

    @Test fun changedBaseUrlWith304RequiresAnUnconditionalBody() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("relative.ts").addHeader("ETag", "v1"))
            server.enqueue(MockResponse().setResponseCode(304))
            server.enqueue(MockResponse().setBody("relative.ts").addHeader("ETag", "v1"))
            val previous = Transport.download(server.url("/old/list").toString(), true, cache.root).use { it.state }
            Transport.download(server.url("/new/list").toString(), true, cache.root, previous).use { body ->
                assertTrue(body.importIfChanged { })
                assertEquals(server.url("/new/list").toString(), body.state.finalUrl)
            }
            server.takeRequest()
            assertEquals("v1", server.takeRequest().getHeader("If-None-Match"))
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            assertTrue(cache.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationDeletesTemporaryBody() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("catalog"))
        var checks = 0
        val error = runCatching {
            Transport.download(server.url("/").toString(), true, cache.root, checkActive = {
                if (++checks == 3) throw kotlinx.coroutines.CancellationException("Refresh stopped")
            })
        }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }
}
