package dev.lumenara.tv

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WatchNextDeviceTest {
    @get:Rule val screen = createEmptyComposeRule()

    private fun published(id: String): WatchNextProgram? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI,
            null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("package_name")) != context.packageName) continue
                val row = WatchNextProgram.fromCursor(cursor)
                if (row.contentId == id) return row
            }
        }
        return null
    }

    @Test fun publishResumeCompleteDisableAndRemoveSource() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val publisher = WatchNext.get(context)
        val oldSetting = runBlocking { prefs.text("watch_next", "Açık").first() }
        val oldEngine = runBlocking { prefs.text("engine", "Media3").first() }
        val state = context.getSharedPreferences("watch_next_state", Context.MODE_PRIVATE)
        val oldExcluded = state.getStringSet("excluded", null)?.toSet()
        val videoRequested = java.util.concurrent.atomic.AtomicReference(java.util.concurrent.CountDownLatch(1))
        val bytes = test.context.assets.open("lumenara-test.mp4").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/list") return MockResponse().setBody("""
                        #EXTM3U
                        #EXTINF:-1 group-title="Film",Watch Next Deneme
                        ${server.url("/movie/test.mp4")}
                    """.trimIndent())
                    if (request.path == "/movie/test.mp4") {
                        videoRequested.get().countDown()
                        return MockResponse().setHeader("Content-Type", "video/mp4")
                            .setBody(Buffer().write(bytes))
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start()
            var source: String? = null
            try {
                val movie = runBlocking {
                    prefs.set("watch_next", "Açık")
                    prefs.set("engine", "Media3")
                    repo.add("Test · Watch Next", SourceSpec(server.url("/list").toString(), true))
                    source = repo.library.snapshot().single { it.name == "Test · Watch Next" }.id
                    val item = repo.browse(source!!, "MOVIE", "", "", "", 1).first().single()
                    repo.updateMark(item.id) { it.copy(position = 1_000, duration = 100_000, updated = 1234) }
                    publisher.sync()
                    item
                }
                val row = checkNotNull(published(movie.id))
                assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, row.watchNextType)
                assertEquals(1_000, row.lastPlaybackPositionMillis)
                assertEquals(100_000, row.durationMillis)
                assertEquals(1234L, row.lastEngagementTimeUtcMillis)
                assertEquals(movie.title, row.title)
                assertEquals(WatchNextPlayback(movie.id, 1_000), parseWatchNextLink(row.intentUri.toString()))
                // Resolve the published URI through the manifest, as the TV launcher does.
                val component = Intent(Intent.ACTION_VIEW, row.intentUri)
                    .setPackage(context.packageName).resolveActivity(context.packageManager)
                assertEquals(MainActivity::class.java.name, checkNotNull(component).className)
                fun playbackIntent() = Intent(Intent.ACTION_VIEW, row.intentUri)
                    .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // ActivityScenario mutates its argument with NEW_TASK | CLEAR_TASK. Never copy
                // that intent for a warm launch. Keep action/data/component identical so its
                // lifecycle monitor still matches after MainActivity.onNewIntent calls setIntent.
                val playback = ActivityScenario.launch<MainActivity>(playbackIntent())
                playback.use { scenario ->
                    fun awaitVideo() {
                        // A blocking latch await starves Compose's test clock and LaunchedEffects.
                        screen.waitUntil(30_000) { videoRequested.get().count == 0L }
                        assertTrue("Deep link video isteği başlatmalı", videoRequested.get().count == 0L)
                        screen.onNodeWithContentDescription("Tam ekran oynatıcı").assertExists()
                    }
                    awaitVideo() // Cold launch: onCreate.
                    lateinit var firstActivity: MainActivity
                    var firstTask = -1
                    scenario.onActivity {
                        firstActivity = it
                        firstTask = it.taskId
                        assertEquals(row.intentUri, it.intent.data)
                        assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
                    }
                    // Back dismisses the initial playback banner before exiting the player.
                    repeat(2) {
                        if (screen.onAllNodesWithContentDescription("Tam ekran oynatıcı").fetchSemanticsNodes().isNotEmpty()) {
                            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                            screen.waitForIdle()
                        }
                    }
                    screen.waitUntil(30_000) {
                        screen.onAllNodesWithContentDescription("Tam ekran oynatıcı").fetchSemanticsNodes().isEmpty()
                    }
                    screen.waitForIdle()
                    // Exercise a stopped app behind TV Home, not a self-launch from the player.
                    assertTrue(UiDevice.getInstance(test).pressHome())
                    screen.waitUntil(30_000) { scenario.state == Lifecycle.State.CREATED }
                    videoRequested.set(java.util.concurrent.CountDownLatch(1))
                    val warmIntent = playbackIntent().putExtra("watch-next-test-warm", true)
                    assertEquals(0, warmIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(warmIntent)
                    screen.waitUntil(30_000) {
                        scenario.state == Lifecycle.State.RESUMED || scenario.state == Lifecycle.State.DESTROYED
                    }
                    // Fail on replacement before attempting to access a destroyed scenario.
                    assertEquals(Lifecycle.State.RESUMED, scenario.state)
                    awaitVideo() // Warm launch: onNewIntent must reuse the existing activity.
                    scenario.onActivity {
                        assertSame(firstActivity, it)
                        assertEquals(firstTask, it.taskId)
                        assertTrue(it.intent.getBooleanExtra("watch-next-test-warm", false))
                        assertEquals(row.intentUri, it.intent.data)
                        assertEquals(WatchNextPlayback(movie.id, 1_000), parseWatchNextLink(it.intent.dataString!!))
                        assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
                    }
                }
                // close() waits for destruction, including when a playback assertion fails.
                // Stop playback before changing history so the player cannot overwrite the fixture.
                assertEquals(Lifecycle.State.DESTROYED, playback.state)
                runBlocking {
                    repo.updateMark(movie.id) { it.copy(position = 95_000, duration = 100_000) }
                    publisher.sync()
                }
                assertNull(published(movie.id))
                runBlocking {
                    repo.updateMark(movie.id) { it.copy(position = 1_000, duration = 100_000) }
                    publisher.sync()
                }
                assertNotNull(published(movie.id))
                runBlocking { prefs.set("watch_next", "Kapalı"); publisher.sync() }
                assertNull(published(movie.id))
                runBlocking { prefs.set("watch_next", "Açık"); publisher.sync() }
                assertNotNull(published(movie.id))
                runBlocking {
                    publisher.exclude(movie.id)
                    publisher.sync()
                    repo.updateMark(movie.id) { it.copy(position = 2_000) }
                    publisher.sync()
                }
                assertNull("Kaldırılan kart ilerleme güncellemesiyle geri gelmemeli", published(movie.id))
                runBlocking {
                    prefs.set("watch_next", "Kapalı")
                    publisher.sync()
                    prefs.set("watch_next", "Açık")
                    publisher.sync()
                }
                assertNull("Kaldırılan kart ayar değişimiyle geri gelmemeli", published(movie.id))
                runBlocking { repo.remove(source!!); publisher.sync() }
                assertNull(published(movie.id))
            } finally {
                runBlocking {
                    source?.let { repo.remove(it) }
                    prefs.set("watch_next", oldSetting)
                    prefs.set("engine", oldEngine)
                    publisher.sync()
                    // exclude() deliberately persists across toggles/restarts; undo the fixture too.
                    val edit = state.edit()
                    if (oldExcluded == null) edit.remove("excluded")
                    else edit.putStringSet("excluded", oldExcluded)
                    check(edit.commit())
                }
            }
        }
    }
}
