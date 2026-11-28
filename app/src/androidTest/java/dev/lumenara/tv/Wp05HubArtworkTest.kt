package dev.lumenara.tv

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import dev.lumenara.data.Item
import dev.lumenara.design.LumenaraTheme
import kotlinx.coroutines.CompletableDeferred
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Wp05HubArtworkTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun delayedCountsAndRealImageResultsCannotCompleteEarly() {
        // Both success and actual HTTP-error fallback must draw; loading text cannot complete.
        for (success in listOf(true, false)) {
            val responseAllowed = CountDownLatch(1)
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (!responseAllowed.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                        return if (success) MockResponse().setHeader("Content-Type", "image/png")
                            .setBody(Buffer().write(png(android.graphics.Color.RED)))
                        else MockResponse().setResponseCode(404)
                    }
                }
                server.start()
                val address = server.url("/delayed").toString()
                val events = CopyOnWriteArrayList<String>()
                val counts = mutableStateOf(false)
                val hub = Wp05HubDraw()
                try {
                    screen.runOnIdle {
                        val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                        screen.activity.setContent {
                            LumenaraTheme {
                                CompositionLocalProvider(LocalWp05HubDraw provides hub) {
                                    Box(Modifier.size(180.dp).onGloballyPositioned { hub.viewport = it.boundsInWindow() }
                                        .drawWithContent {
                                            drawContent()
                                            hub.onDraw(true, counts.value, true, events::add)
                                        }) {
                                        Artwork(model, item("delayed"), address, Modifier.fillMaxSize(), allowHttp = true)
                                    }
                                }
                            }
                        }
                    }
                    screen.waitUntil(10_000) { server.requestCount == 1 }
                    screen.runOnIdle { assertFalse(events.contains("startup_hub_full_draw")) }
                    if (success) {
                        // Counts are ready, image still in-flight.
                        screen.runOnIdle { counts.value = true }
                        screen.waitForIdle()
                        assertFalse(events.contains("startup_hub_full_draw"))
                        responseAllowed.countDown()
                    } else {
                        // Error fallback is drawn first; delayed counts must still block full.
                        responseAllowed.countDown()
                        screen.waitUntil(10_000) { events.contains("startup_hub_artwork_draw") }
                        assertFalse(events.contains("startup_hub_full_draw"))
                        screen.runOnIdle { counts.value = true }
                    }
                    screen.waitUntil(10_000) { events.contains("startup_hub_full_draw") }
                    assertEquals(1, events.count { it == "startup_hub_full_draw" })
                } finally {
                    responseAllowed.countDown()
                    screen.runOnUiThread { screen.activity.setContent {} }
                }
            }
        }
    }

    @Test fun replacingItemInStableSlotClearsOldPosterBeforeDelayedResolution() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(png(android.graphics.Color.RED))))
            server.start()
            val address = server.url("/first").toString()
            val current = mutableStateOf(item("First"))
            val secondAddress = CompletableDeferred<String?>()
            try {
                screen.runOnIdle {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        LumenaraTheme {
                            val row = current.value
                            Box(Modifier.size(120.dp, 180.dp).testTag("stable-slot")) {
                                Wp05PosterImage(row, { if (row.id == "First") address else secondAddress.await() }) { image ->
                                    Artwork(model, row, image, Modifier.fillMaxSize(), allowHttp = true)
                                }
                            }
                        }
                    }
                }
                screen.waitUntil(10_000) {
                    server.requestCount == 1 && screen.onAllNodesWithText("First").fetchSemanticsNodes().isEmpty()
                }
                val old = screen.onNodeWithTag("stable-slot").captureToImage().toPixelMap()
                assertTrue(old[old.width / 2, old.height / 2].red > .95f)
                screen.runOnIdle { current.value = item("Second") }
                screen.onNodeWithText("Second").assertIsDisplayed()
                val pending = screen.onNodeWithTag("stable-slot").captureToImage().toPixelMap()
                assertTrue("Old red poster must disappear while the new address is unresolved",
                    pending[pending.width / 2, pending.height / 2].red < .5f)
                screen.runOnIdle { secondAddress.complete(null) }
                screen.onNodeWithText("Second").assertIsDisplayed()
            } finally {
                secondAddress.cancel()
                screen.runOnUiThread { screen.activity.setContent {} }
            }
        }
    }

    private fun item(id: String) = Item(id = id, source = "", title = id, category = "", kind = "MOVIE",
        tvgId = "", encryptedUrl = "", search = "", generation = 0, logo = "")

    private fun png(color: Int) = ByteArrayOutputStream().also { output ->
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let {
            it.eraseColor(color)
            it.compress(Bitmap.CompressFormat.PNG, 100, output)
            it.recycle()
        }
    }.toByteArray()
}
