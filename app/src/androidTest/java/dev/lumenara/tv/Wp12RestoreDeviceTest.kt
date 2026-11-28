package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class Wp12RestoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = Preferences(context)
    private val pendingKey = "secret_wp12_pending_restore"
    private val orphanKey = "secret_orphan_history"

    private suspend fun fixture(test: suspend (Repository) -> Unit) {
        val repo = Repository.awaitReady(context)
        assertTrue("An isolated test library is required", repo.library.snapshot().all { it.name.startsWith("Test · ") })
        val oldAdult = prefs.showAdult.first()
        val oldRecent = prefs.text("recent_live").first()
        val oldPending = prefs.text(pendingKey).first()
        val oldOrphans = prefs.text(orphanKey).first()
        try {
            repo.library.snapshot().forEach { repo.remove(it.id) }
            prefs.set(pendingKey, "")
            prefs.set(orphanKey, "")
            prefs.set("recent_live", "")
            test(repo)
        } finally {
            repo.library.snapshot().filter { it.name.startsWith("Test · WP12") }.forEach { repo.remove(it.id) }
            prefs.setShowAdult(oldAdult)
            prefs.set("recent_live", oldRecent)
            prefs.set(pendingKey, oldPending)
            prefs.set(orphanKey, oldOrphans)
        }
    }

    private fun playlist(server: MockWebServer, vararg paths: String) = "#EXTM3U\n" + paths.joinToString("\n") {
        "#EXTINF:-1 tvg-name=\"WP12 Channel\" group-title=\"News\",WP12 Channel\n${server.url(it)}"
    }

    @Test fun changedPlaylistUrlRestoresMarkAndRecentWithoutConsumingLocalDeletionPool() = runBlocking {
        fixture { repo ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setBody(playlist(server, "/old.ts")))
                repo.add("Test · WP12 locator", SourceSpec(server.url("/list").toString(), true))
                val source = repo.library.snapshot().single()
                val old = repo.library.browse(source.id, "LIVE", "", "", "", 10).first().single()
                val saved = Mark(old.id, true, true, 500, 1000, 10)
                repo.library.save(saved)
                prefs.recordLive(old.id)
                val preview = repo.previewBackup(repo.createBackup("wp12-password".toCharArray()), "wp12-password".toCharArray())
                // A newer device-local deletion snapshot must remain untouched by restore.
                repo.library.save(saved.copy(position = 900, updated = 20))
                repo.remove(source.id)
                val localPool = prefs.text(orphanKey).first()
                assertTrue(localPool.isNotEmpty())
                server.enqueue(MockResponse().setBody(playlist(server, "/new.m3u8")))
                val result = repo.restoreBackup(preview)
                val target = repo.library.browse(source.id, "LIVE", "", "", "", 10).first().single()
                assertNotEquals(old.id, target.id)
                assertEquals(0, result.skipped)
                assertEquals(saved.copy(id = target.id), repo.library.mark(target.id))
                assertEquals(listOf(target.id), prefs.recentLive.first())
                assertEquals(localPool, prefs.text(orphanKey).first())
                assertEquals("", prefs.text(pendingKey).first())
            }
        }
    }

    @Test fun ambiguousRestoreIsEncryptedAndLaterRefreshRetriesOnlyItsItemState() = runBlocking {
        fixture { repo ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setBody(playlist(server, "/old.ts")))
                repo.add("Test · WP12 pending", SourceSpec(server.url("/list").toString(), true))
                val source = repo.library.snapshot().single()
                val old = repo.library.browse(source.id, "LIVE", "", "", "", 10).first().single()
                val saved = Mark(old.id, true, true, 500, 1000, 10)
                repo.library.save(saved)
                prefs.recordLive(old.id)
                prefs.setShowAdult(false)
                val preview = repo.previewBackup(repo.createBackup("wp12-password".toCharArray()), "wp12-password".toCharArray())
                repo.remove(source.id)
                // Keep this test's later result attributable solely to the restore queue.
                prefs.set(orphanKey, "")
                server.enqueue(MockResponse().setBody(playlist(server, "/a.ts", "/b.ts")))
                assertEquals(1, repo.restoreBackup(preview).skipped)
                val ciphertext = prefs.text(pendingKey).first()
                assertTrue(ciphertext.isNotEmpty())
                assertFalse(ciphertext.contains(server.hostName))
                assertFalse(ciphertext.contains("WP12 Channel"))
                val ambiguous = repo.library.browse(source.id, "LIVE", "", "", "", 10).first()
                assertEquals(2, ambiguous.size)
                assertTrue(ambiguous.all { repo.library.mark(it.id) == null })
                assertTrue(prefs.recentLive.first().isEmpty())
                prefs.setShowAdult(true)
                server.enqueue(MockResponse().setBody(playlist(server, "/resolved.ts")))
                repo.refresh(source.id)
                val resolved = repo.library.browse(source.id, "LIVE", "", "", "", 10).first().single()
                assertEquals(saved.copy(id = resolved.id), repo.library.mark(resolved.id))
                assertEquals(listOf(resolved.id), prefs.recentLive.first())
                assertTrue(prefs.showAdult.first())
                assertEquals("", prefs.text(pendingKey).first())
            }
        }
    }

    @Test fun everyRestoreHttpRequestIncludingLazyEpisodesAllowsAnIndependentRoomWriter() = runBlocking {
        fixture { repo ->
            val extension = AtomicReference("mp4")
            val probing = AtomicBoolean(false)
            val guardedRequests = AtomicInteger()
            val blockedWriters = AtomicInteger()
            val probe = AtomicReference<Mark?>()
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (probing.get()) {
                            guardedRequests.incrementAndGet()
                            val failure = runCatching {
                                // A suspend DAO write uses Room's transaction executor. If restore
                                // is holding a transaction while awaiting HTTP, this cannot finish.
                                runBlocking { withTimeout(5_000) { repo.library.save(checkNotNull(probe.get())) } }
                            }.exceptionOrNull()
                            if (failure != null) {
                                blockedWriters.incrementAndGet()
                                return MockResponse().setResponseCode(503)
                            }
                        }
                        val body = when (request.requestUrl?.queryParameter("action")) {
                            null -> """{"user_info":{"auth":1}}"""
                            "get_live_streams" -> """[{"stream_id":17,"name":"WP12 Live"}]"""
                            "get_series" -> """[{"series_id":10,"name":"WP12 Show"}]"""
                            "get_series_info" -> """{"episodes":{"1":[{"id":"101","episode_num":1,"title":"Episode","container_extension":"${extension.get()}"}]}}"""
                            else -> "[]"
                        }
                        return MockResponse().setBody(body)
                    }
                }
                server.start()
                repo.add("Test · WP12 network boundary", SourceSpec(server.url("/").toString(), true, "u", "p"))
                val source = repo.library.snapshot().single()
                val series = repo.library.browse(source.id, "SERIES", "", "", "", 10).first().single()
                val episode = repo.expand(series).single()
                val saved = Mark(episode.id, true, true, 500, 1000, 10)
                repo.library.save(saved)
                val preview = repo.previewBackup(repo.createBackup("wp12-password".toCharArray()), "wp12-password".toCharArray())
                val live = repo.library.browse(source.id, "LIVE", "", "", "", 10).first().single()
                probe.set(Mark(live.id))
                extension.set("mkv")
                probing.set(true)
                try {
                    assertEquals(0, repo.restoreBackup(preview).skipped)
                } finally { probing.set(false) }
                // Account + six top-level endpoints + the backed-up lazy episode parent.
                assertEquals(8, guardedRequests.get())
                assertEquals(0, blockedWriters.get())
                val currentSeries = checkNotNull(repo.library.item(series.id))
                val restored = repo.library.episodeSnapshot(currentSeries.id).single()
                assertNotEquals(episode.id, restored.id)
                assertEquals(saved.copy(id = restored.id), repo.library.mark(restored.id))
            }
        }
    }
}
