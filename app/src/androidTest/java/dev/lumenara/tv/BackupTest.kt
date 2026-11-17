package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.catalog.contentKey
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = Repository.get(context)
    private val prefs = Preferences(context)

    private suspend fun clearTestSources() {
        val sources = repo.library.snapshot()
        assumeTrue("An isolated test library is required.", sources.all { it.name.startsWith("Test · ") })
        sources.forEach { repo.remove(it.id) }
    }

    @Test fun previewThenRestoreRemapsExistingSourceAndReencryptsSecrets() = runBlocking {
        val oldAdult = prefs.showAdult.first()
        val oldRecent = prefs.text("recent_live").first()
        val oldSecret = prefs.secret("backup_test")
        val absentKey = "backup_missing_${java.util.UUID.randomUUID()}"
        val name = "Test · Yedek"
        // Keep the programme inside the production guide retention window on every run.
        val now = java.time.Instant.now()
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
            .withZone(java.time.ZoneOffset.UTC)
        val start = stamp.format(now.minusSeconds(3600))
        val stop = stamp.format(now.plusSeconds(3600))
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/list" -> MockResponse().setBody("""
                        #EXTM3U
                        #EXTINF:-1 tvg-id="channel" group-title="Haber",Yedek Kanal
                        ${server.url("/live/channel.ts")}
                    """.trimIndent())
                    "/guide" -> MockResponse().setBody("""<tv><channel id="channel"><display-name>Yedek Kanal</display-name></channel>
                        <programme channel="channel" start="$start" stop="$stop"><title>Haber</title></programme></tv>""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            try {
                // Instrumentation uses the dedicated debug app, with only local test sources.
                clearTestSources()
                val spec = SourceSpec(server.url("/list").toString(), true)
                repo.add(name, spec)
                val source = repo.library.snapshot().single()
                val channel = repo.library.browse(source.id, "LIVE", "", "", "", 10).first().single()
                repo.addGuide(source.id, server.url("/guide").toString())
                val feed = repo.guide.feeds(source.id).single()
                repo.guide.map(Mapping(channel.id, feed.id, "channel"))
                repo.updateMark(channel.id) { Mark(it.id, true, true, 500, 1000, 123) }
                prefs.recordLive(channel.id)
                prefs.setShowAdult(true)
                prefs.setSecret("backup_test", "portable-private-secret")
                prefs.set("limit_${source.id}", "3")
                val bytes = repo.createBackup("parola-123".toCharArray())
                assertFalse(bytes.toString(Charsets.UTF_8).contains("portable-private-secret"))

                repo.remove(source.id)
                repo.add("Test · Mevcut", spec)
                val target = repo.library.snapshot().single()
                assertNotEquals(source.id, target.id)
                prefs.setShowAdult(false)
                prefs.setSecret("backup_test", "changed")
                prefs.set("recent_live", "")
                prefs.set(absentKey, "added after backup")
                val preview = repo.previewBackup(bytes, "parola-123".toCharArray())
                assertTrue(preview.summary.contains("1 kaynak"))
                assertTrue(preview.summary.contains("1 izleme kaydı"))
                assertFalse(prefs.showAdult.first())
                assertEquals("changed", prefs.secret("backup_test"))
                assertEquals("Test · Mevcut", repo.library.source(target.id)?.name)

                val result = repo.restoreBackup(preview)
                val restoredId = contentKey(target.id, server.url("/live/channel.ts").toString())
                assertEquals(0, result.skipped)
                assertEquals(0, result.guideFailures)
                assertEquals(1, repo.library.snapshot().size)
                assertEquals(name, repo.library.source(target.id)?.name)
                assertEquals(Mark(restoredId, true, true, 500, 1000, 123), repo.library.mark(restoredId))
                assertTrue(prefs.showAdult.first())
                assertEquals(listOf(restoredId), prefs.recentLive.first())
                assertEquals("3", prefs.text("limit_${target.id}").first())
                assertEquals("portable-private-secret", prefs.secret("backup_test"))
                assertEquals("default", prefs.text(absentKey, "default").first())
                val storedSecret = prefs.text("secret_backup_test").first()
                assertFalse(storedSecret.contains("portable-private-secret"))
                val restoredFeed = contentKey(target.id, server.url("/guide").toString())
                assertEquals(Mapping(restoredId, restoredFeed, "channel"), repo.guide.mapping(restoredId).first())
                assertEquals(1, repo.guide.guide(target.id, "channel", "", 0, Long.MAX_VALUE, "").first().size)
            } finally {
                repo.library.snapshot().filter { it.name.startsWith("Test · ") }.forEach { repo.remove(it.id) }
                prefs.setShowAdult(oldAdult)
                prefs.set("recent_live", oldRecent)
                prefs.setSecret("backup_test", oldSecret)
            }
        }
    }

    @Test fun sourceRefreshFailureRollsBackBeforeApplyingSettings() = runBlocking {
        val name = "Test · Yedek geri alma"
        val oldAdult = prefs.showAdult.first()
        MockWebServer().use { server ->
            server.start()
            try {
                clearTestSources()
                val list = "#EXTM3U\n#EXTINF:-1,Kanal\n${server.url("/stream")}"
                for (number in 1..2) {
                    server.enqueue(MockResponse().setBody(list))
                    repo.add("$name $number", SourceSpec(server.url("/list$number").toString(), true))
                }
                val sources = repo.library.snapshot()
                prefs.setShowAdult(true)
                val bytes = repo.createBackup("parola-123".toCharArray())
                val preview = repo.previewBackup(bytes, "parola-123".toCharArray())
                sources.forEach { repo.remove(it.id) }
                prefs.setShowAdult(false)
                // The first source is written successfully, then the second refresh fails.
                server.enqueue(MockResponse().setBody(list))
                server.enqueue(MockResponse().setResponseCode(503))
                assertTrue(runCatching { repo.restoreBackup(preview) }.isFailure)
                assertTrue(repo.library.snapshot().isEmpty())
                assertFalse(prefs.showAdult.first())
            } finally {
                repo.library.snapshot().filter { it.name.startsWith(name) }.forEach { repo.remove(it.id) }
                prefs.setShowAdult(oldAdult)
            }
        }
    }

    @Test fun sharedDownloadContainsExactlyEncryptedBytes() = runBlocking<Unit> {
        if (android.os.Build.VERSION.SDK_INT < 29) return@runBlocking
        val bytes = repo.createBackup("parola-123".toCharArray())
        val filename = BackupFiles.filename()
        BackupFiles.save(context, bytes, filename)
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(collection, arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(filename), null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            try {
                assertEquals(uri, BackupFiles.localBackups(context).single { it.name == filename }.uri)
                val stored = resolver.openInputStream(uri)!!.use(BackupFiles::read)
                assertArrayEquals(bytes, stored)
                repo.previewBackup(stored, "parola-123".toCharArray())
            } finally { resolver.delete(uri, null, null) }
        }
    }
}
