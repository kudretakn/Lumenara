@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package dev.lumenara.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.SuccessResult
import dev.lumenara.data.Transport
import dev.lumenara.data.Item
import dev.lumenara.design.LumenaraTheme
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

class ArtworkTest {
    @Test fun fictionalPostersAndBackdropsHaveTheirOwnAspectRatios() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        (0..6).forEach { index ->
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open("cinema/tile$index.jpg").use { BitmapFactory.decodeStream(it, null, size) }
            assertEquals(2 * size.outHeight, 3 * size.outWidth)
            assertTrue(size.outWidth > 0)
        }
        listOf("backdrop", "channel").forEach { name ->
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open("cinema/$name.jpg").use { BitmapFactory.decodeStream(it, null, size) }
            assertEquals(9 * size.outWidth, 16 * size.outHeight)
            assertTrue(size.outWidth > 0)
        }
    }

    @Test fun tmdbVariantsAndDecodeSizeMatchTheRequestedRole() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://image.tmdb.org/t/p/original/image.jpg"
        val poster = artworkRequest(context, url, false, 232, 300)
        assertEquals("https://image.tmdb.org/t/p/w342/image.jpg", poster.data)
        assertEquals(coil.size.Size(232, 300), poster.sizeResolver.size())
        assertTrue(poster.allowRgb565)
        assertEquals("https://image.tmdb.org/t/p/w185/image.jpg",
            artworkRequest(context, url, false, 88, 88, "person").data)
        assertEquals("https://image.tmdb.org/t/p/w780/image.jpg",
            artworkRequest(context, url, false, 720, 405, "backdrop").data)
        assertEquals("https://image.tmdb.org/t/p/w1280/image.jpg",
            artworkRequest(context, url, false, 1920, 1080, "backdrop").data)
    }

    @Test fun hubPosterFallbackUsesPosterVariantAndCacheKeyWhileBackdropKeepsItsRole() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://image.tmdb.org/t/p/original/hub.jpg"
        val fallback = wp05ResolveHubImage({ "" }, { url })
        assertEquals("poster", fallback.role)
        val hubPoster = artworkRequest(context, checkNotNull(fallback.address), false, 720, 405, fallback.role)
        val regularPoster = artworkRequest(context, url, false, 232, 348)
        assertEquals("https://image.tmdb.org/t/p/w342/hub.jpg", hubPoster.data)
        assertEquals(regularPoster.diskCacheKey, hubPoster.diskCacheKey)
        assertEquals(regularPoster.memoryCacheKey, hubPoster.memoryCacheKey)
        var posterCalled = false
        val wide = wp05ResolveHubImage({ url }, { posterCalled = true; url })
        assertFalse(posterCalled)
        assertEquals("backdrop", wide.role)
        assertEquals("https://image.tmdb.org/t/p/w780/hub.jpg",
            artworkRequest(context, checkNotNull(wide.address), false, 720, 405, wide.role).data)
        val missing = wp05ResolveHubImage({ null }, { null })
        assertNull(missing.address)
    }

    @Test fun missingImageIsRequestedOnlyOncePerSession() = runBlocking {
        withLoader { loader, _, server, _ ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            val request = artworkRequest(InstrumentationRegistry.getInstrumentation().targetContext,
                server.url("/missing").toString(), true, 36, 36)
            assertTrue(loader.execute(request) is ErrorResult)
            assertTrue(loader.execute(request) is ErrorResult)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun ignoresOriginNoCacheButExpiresAfterSevenDays() = runBlocking {
        withLoader { loader, _, server, advanceWeek ->
            val png = ByteArrayOutputStream().also { output ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let {
                    it.compress(Bitmap.CompressFormat.PNG, 100, output)
                    it.recycle()
                }
            }.toByteArray()
            repeat(2) {
                server.enqueue(MockResponse().setHeader("Cache-Control", "no-cache, no-store")
                    .setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            }
            val request = artworkRequest(InstrumentationRegistry.getInstrumentation().targetContext,
                server.url("/poster").toString(), true, 36, 36)
            assertTrue(loader.execute(request) is SuccessResult)
            loader.memoryCache?.clear()
            assertTrue(loader.execute(request) is SuccessResult)
            assertEquals(1, server.requestCount)
            advanceWeek()
            loader.memoryCache?.clear()
            assertTrue(loader.execute(request) is SuccessResult)
            assertEquals(2, server.requestCount)
        }
    }

    private suspend fun withLoader(block: suspend (ImageLoader, DiskCache, MockWebServer, () -> Unit) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "image-test-${System.nanoTime()}")
        val disk = DiskCache.Builder().directory(directory).maxSizeBytes(1024 * 1024).build()
        val memory = MemoryCache.Builder(context).maxSizeBytes(1024 * 1024).build()
        var clock = System.currentTimeMillis()
        val loader = ImageLoader.Builder(context).okHttpClient(Transport.imageClient())
            .diskCache(disk).memoryCache(memory).respectCacheHeaders(false)
            .components { add(ArtworkInterceptor(disk, memory) { clock }) }.build()
        try {
            MockWebServer().use { server ->
                server.start()
                block(loader, disk, server) { clock += 8L * 24 * 60 * 60 * 1000 }
            }
        } finally {
            loader.shutdown()
            directory.deleteRecursively()
        }
    }
}

/** Exercise the actual Coil success/error states, rather than just a placeholder preview. */
class ArtworkFallbackDeviceTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun missingAndFailedImagesKeepTitlesWhileSuccessfulImagesReplaceThem() {
        val png = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(android.graphics.Color.RED)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            }
        }.toByteArray()
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.path == "/image.png") MockResponse()
                        .setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                    else MockResponse().setResponseCode(404)
            }
            server.start()
            // MockWebServer.url can perform reverse DNS; resolve before entering Main/Compose.
            val missingUrl = server.url("/missing").toString()
            val imageUrl = server.url("/image.png").toString()
            fun item(title: String, kind: String) = Item(
                id = title, source = "", title = title, category = "", kind = kind,
                tvgId = "", encryptedUrl = "", search = "", generation = 0, logo = "",
            )
            screen.runOnIdle {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        Row(Modifier.padding(48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Artwork(model, item("Kıyı Yolculuğu", "SERIES"), null,
                                Modifier.size(112.dp, 168.dp), allowHttp = true)
                            Artwork(model, item("Eksik Film", "MOVIE"), missingUrl,
                                Modifier.size(112.dp, 168.dp), allowHttp = true)
                            Artwork(model, item("Nova Sports HD", "LIVE"), null,
                                Modifier.size(204.dp, 115.dp), allowHttp = true)
                            Artwork(model, item("Görselli Film", "MOVIE"), imageUrl,
                                Modifier.size(112.dp, 168.dp).testTag("loaded-artwork"), allowHttp = true)
                        }
                    }
                }
            }
            screen.waitUntil(10_000) {
                server.requestCount >= 2 &&
                    screen.onAllNodesWithText("Görselli Film").fetchSemanticsNodes().isEmpty()
            }
            screen.onNodeWithText("Kıyı Yolculuğu").assertIsDisplayed()
            screen.onNodeWithText("Eksik Film").assertIsDisplayed()
            screen.onNodeWithText("Nova Sports").assertIsDisplayed()
            screen.onNodeWithText("Nova Sports HD").assertDoesNotExist()
            screen.onNodeWithText("K").assertDoesNotExist()
            // Successful artwork is not covered by its typographic fallback.
            val pixels = screen.onNodeWithTag("loaded-artwork").captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            assertTrue(center.red > .95f && center.green < .05f && center.blue < .05f)
        }
    }
}
