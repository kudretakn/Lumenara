@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package dev.lumenara.tv

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.SuccessResult
import dev.lumenara.data.Transport
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ArtworkTest {
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
