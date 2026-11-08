package dev.streamora.tv

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.streamora.data.Repository
import dev.streamora.data.SourceSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class LargeCatalogTest {
    @Test
    fun importsAndSearchesTenThousandChannels() = runBlocking {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = "Test · Büyük katalog"
        MockWebServer().use { server ->
            server.start()
            val list = buildString {
                appendLine("#EXTM3U")
                repeat(10000) { id ->
                    appendLine("#EXTINF:-1 group-title=\"Haber\",Yerel Kanal $id")
                    appendLine("https://example.invalid/live/$id")
                }
            }
            server.enqueue(MockResponse().setBody(list))
            try {
                val start = SystemClock.elapsedRealtime()
                repo.add(name, SourceSpec(server.url("/list").toString(), true))
                val importMs = SystemClock.elapsedRealtime() - start
                val source = repo.library.snapshot().single { it.name == name }
                assertEquals(10000, source.count)
                val queryStart = SystemClock.elapsedRealtime()
                val result =
                    repo.library.browse(source.id, "LIVE", "", "kanal 9999", "", 80).first()
                val queryMs = SystemClock.elapsedRealtime() - queryStart
                assertEquals("Yerel Kanal 9999", result.single().title)
                Log.i("StreamoraBenchmark", "rows=10000 importMs=$importMs queryMs=$queryMs")
            } finally {
                repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
            }
        }
        Unit
    }
}
