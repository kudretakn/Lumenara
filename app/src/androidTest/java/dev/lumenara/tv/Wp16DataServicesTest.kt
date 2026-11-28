package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.catalog.Kind
import dev.lumenara.catalog.Listing
import dev.lumenara.catalog.Playlist
import dev.lumenara.catalog.SeriesGrouper
import dev.lumenara.catalog.contentKey
import dev.lumenara.data.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Real Room/Vault coverage only; no UI, remote-key timing or application data clearing. */
class Wp16DataServicesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun facadeImportAndReadResultsMatchTheOriginalParserAndDaoFixtures() = runBlocking {
        withTimeout(20_000) {
            val repo = Repository.awaitReady(context)
            val name = "Test · WP16 · ${UUID.randomUUID()}"
            var sourceId: String? = null
            MockWebServer().use { server ->
                val body = """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="news" group-title="Haber",WP16 Haber
                    https://private.invalid/live/wp16-user/wp16-password/1.ts
                    #EXTINF:-1 group-title="Filmler",WP16 Film
                    movie.mp4
                    #EXTINF:-1 group-title="Diziler",WP16 Dizi S1E2
                    episode.mp4
                """.trimIndent()
                val url = server.url("/prefix/list").toString()
                val expected = mutableListOf<Listing>()
                val grouper = SeriesGrouper()
                Playlist.read(body.reader(), URI(url), true) { row -> grouper.accept(row, expected::add) }
                server.enqueue(MockResponse().setBody(body).addHeader("ETag", "wp16-v1"))
                server.enqueue(MockResponse().setResponseCode(304))
                server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:8\n"))
                try {
                    repo.add(name, SourceSpec(url, true))
                    val source = repo.sourceSnapshot().single { it.name == name }
                    sourceId = source.id
                    val wrappedKey = context.getSharedPreferences("vault", 0).getString("data-key", null)
                    assertNotNull(wrappedKey)
                    assertEquals(SourceSpec(url, true), repo.sourceSpec(source.id))
                    val hidden = repo.hidden.first()
                    for (kind in listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)) {
                        val expectedIds = expected.filter { it.kind == kind }.map { contentKey(source.id, it.url) }.toSet()
                        val actual = repo.browse(source.id, kind.name, "", "", "", 20).first()
                        assertEquals(expectedIds, actual.map { it.id }.toSet())
                        for (row in actual) {
                            val listing = expected.single { contentKey(source.id, it.url) == row.id }
                            assertEquals(listing.url, repo.address(row))
                            assertEquals(listing.title, row.title)
                            assertEquals(listing.group, row.category)
                        }
                        val reference = repo.library.browsePage(source.id, kind.name, "", "", "", 20, 0,
                            hidden = hidden).first()
                        assertEquals(reference, repo.browsePage(source.id, kind.name, "", "", "", 20, 0).first())
                        assertEquals(repo.library.browseTotal(source.id, kind.name, "", "", "", hidden = hidden).first(),
                            repo.browseCount(source.id, kind.name, "", "", "").first())
                    }
                    val series = repo.browse(source.id, "SERIES", "", "", "", 20).first().single()
                    assertEquals(expected.filter { it.kind == Kind.EPISODE }.map { contentKey(source.id, it.url) },
                        repo.episodeSnapshot(series.id).map { it.id })
                    val channel = repo.livePage(source.id, "", "", "", 20, 0).first().single()
                    assertEquals(repo.library.livePage(source.id, "", "", "", 20, 0, hidden).first(),
                        repo.livePage(source.id, "", "", "", 20, 0).first())
                    repo.updateMark(channel.id) { it.copy(favorite = true, listed = true, position = 120L, updated = 9L) }
                    val mark = repo.library.mark(channel.id)
                    repo.refresh(source.id) // A 304 cannot drop rows or user state.
                    assertEquals(mark, repo.library.mark(channel.id))
                    server.takeRequest()
                    assertEquals("wp16-v1", server.takeRequest().getHeader("If-None-Match"))
                    assertEquals(wrappedKey, context.getSharedPreferences("vault", 0).getString("data-key", null))
                    assertEquals(url, repo.sourceSpec(source.id).address)
                    assertTrue(runCatching { repo.refresh(source.id) }.isFailure)
                    assertEquals(mark, repo.library.mark(channel.id))
                    assertEquals(channel.id, repo.livePage(source.id, "", "", "", 20, 0).first().single().id)
                } finally {
                    sourceId?.let { repo.library.deleteSource(it) }
                }
            }
        }
    }

    @Test fun actualVaultBackedDownloadHasNoPlaintextCredentialsWhileInFlight() = runBlocking {
        withTimeout(20_000) {
            val repo = Repository.awaitReady(context)
            val unique = "wp16-credential-${UUID.randomUUID()}"
            val name = "Test · WP16 · $unique"
            MockWebServer().use { server ->
                val body = buildString {
                    appendLine("#EXTM3U")
                    repeat(2000) { index ->
                        appendLine("#EXTINF:-1 group-title=\"Haber\",WP16 $index")
                        appendLine("https://private.invalid/live/user/$unique/$index.ts")
                    }
                }
                server.enqueue(MockResponse().setBody(body).throttleBody(65536, 50, TimeUnit.MILLISECONDS))
                val existingSpools = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
                val adding = async(Dispatchers.IO) { repo.add(name, SourceSpec(server.url("/list").toString(), true)) }
                try {
                    val onDisk = withTimeout(5_000) {
                        var captured: ByteArray? = null
                        while (captured == null) {
                            val file = context.cacheDir.listFiles().orEmpty().firstOrNull {
                                it.name !in existingSpools && it.name.startsWith("wp16-spool-") && it.length() > 65536
                            }
                            captured = file?.let { runCatching { it.readBytes() }.getOrNull() }
                            if (captured == null) delay(5)
                        }
                        checkNotNull(captured)
                    }
                    val text = onDisk.toString(Charsets.ISO_8859_1)
                    assertFalse(text.contains(unique))
                    assertFalse(text.contains("https://private.invalid"))
                    adding.await()
                    val source = repo.sourceSnapshot().single { it.name == name }
                    assertEquals(2000, source.count)
                    assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name !in existingSpools && it.name.startsWith("wp16-spool-") })
                } finally {
                    adding.cancelAndJoin()
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.library.deleteSource(it.id) }
                }
            }
        }
    }
}
