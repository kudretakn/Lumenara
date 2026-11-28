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

    @Test(timeout = 35_000) fun media3CancelsTricklingPreparationAndRetries() = trickle("Media3")
    @Test(timeout = 35_000) fun vlcCancelsTricklingPreparationAndRetries() = trickle("VLC")

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
                assertEquals("/list", server.takeRequest(2, TimeUnit.SECONDS)?.path)
                val mounted = java.util.concurrent.CountDownLatch(1)
                val start = SystemClock.elapsedRealtime()
                screen.runOnUiThread {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        LumenaraTheme {
                            DisposableEffect(Unit) { mounted.countDown(); onDispose { } }
                            Playback(model, channel, listOf(channel), {}, {}, {})
                        }
                    }
                }
                assertTrue(mounted.await(2, TimeUnit.SECONDS))
                assertEquals("/live.ts", server.takeRequest(15, TimeUnit.SECONDS)?.path)
                val remaining = (start + 19_000 - SystemClock.elapsedRealtime()).coerceAtLeast(1)
                assertEquals("The 15s preparation budget must cancel and schedule the 1s retry",
                    "/live.ts", server.takeRequest(remaining, TimeUnit.MILLISECONDS)?.path)
                assertTrue("Retry is bounded independently of the 25s socket timeout",
                    SystemClock.elapsedRealtime() - start < 19_000)
            } finally {
                screen.runOnUiThread { screen.activity.setContent {} }
                runBlocking {
                    saved.forEach { (key, value) -> prefs.set(key, value) }
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                }
            }
        }
    }
}
