package dev.lumenara.tv

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Preferences
import dev.lumenara.data.Repository
import dev.lumenara.data.SourceSpec
import dev.lumenara.design.LumenaraTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Wp09PlaybackPreparationTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    // Includes source setup, composition, the real 15s budget, retry and cleanup.
    @Test(timeout = 90_000) fun media3CancelsTricklingPreparationAndRetries() = trickle("Media3")
    @Test(timeout = 90_000) fun vlcCancelsTricklingPreparationAndRetries() = trickle("VLC")

    private fun trickle(engine: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val name = "Test · WP09 $engine"
        val saved = runBlocking {
            listOf("engine", "fallback", "recent_live").associateWith { prefs.text(it).first() }
        }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/list") return MockResponse().setBody(
                        "#EXTM3U\n#EXTINF:-1,WP09\n${server.url("/live.ts")}\n")
                    // Trickle below a socket's read timeout, without enough data for a frame.
                    // This distinguishes the total preparation budget from per-read timeouts.
                    return MockResponse().setBody(Buffer().write(ByteArray(128)))
                        .throttleBody(1, 500, TimeUnit.MILLISECONDS)
                        .setHeader("Content-Length", 1_000_000)
                }
            }
            server.start()
            try {
                val channel = runBlocking {
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                    prefs.set("engine", engine)
                    prefs.set("fallback", "Kapalı")
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                    val source = repo.library.snapshot().single { it.name == name }
                    repo.livePage(source.id, "", "", "", 1, 0).first().single()
                }
                assertEquals("/list", server.takeRequest(10, TimeUnit.SECONDS)?.path)
                val mounted = java.util.concurrent.CountDownLatch(1)
                screen.runOnUiThread {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        LumenaraTheme {
                            DisposableEffect(Unit) { mounted.countDown(); onDispose { } }
                            Playback(model, channel, listOf(channel), {}, {}, {})
                        }
                    }
                }
                // waitUntil advances Compose frames; blocking on the latch or request
                // queue prevents composition/retry effects from running under this rule.
                screen.waitUntil(15_000) { mounted.count == 0L }
                awaitRequest(server, 15_000)
                val start = SystemClock.elapsedRealtime()
                // The JVM test pins the deadline to exactly 15s. Allow bounded
                // native cleanup/scheduling overhead here on a slow emulator.
                awaitRequest(server, 22_000)
                assertTrue("Retry is bounded independently of the 25s socket timeout",
                    SystemClock.elapsedRealtime() - start < 25_000)
            } finally {
                screen.runOnUiThread { screen.activity.setContent {} }
                screen.waitForIdle()
                runBlocking {
                    saved.forEach { (key, value) -> prefs.set(key, value) }
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                }
            }
        }
    }

    private fun awaitRequest(server: MockWebServer, timeoutMs: Long) {
        var request: RecordedRequest? = null
        screen.waitUntil(timeoutMs) {
            server.takeRequest(0, TimeUnit.MILLISECONDS)?.also { request = it } != null
        }
        assertEquals("Expected a playback request from the selected engine", "/live.ts", request?.path)
    }
}
