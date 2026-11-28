package dev.lumenara.data

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Wp16XtreamSpoolTest {
    @get:Rule val cache = TemporaryFolder()
    private val actions = listOf("get_live_categories", "get_live_streams", "get_vod_categories",
        "get_vod_streams", "get_series_categories", "get_series")
    private val secret = "wp16-xtream-password"
    private val body = """[{"name":"Türkçe","stream_icon":"https://private.invalid/user/$secret/logo.png"}]"""

    @Test fun snapshotHasTheLegacyDigestWithEncryptedEndpointBodiesAndUnchangedAccountNoise() = MockWebServer().use { server ->
        val xtream = Xtream(SourceSpec(server.url("/").toString(), true, "user", secret))
        fun enqueue(accountTime: Int) {
            server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1,"timestamp":$accountTime},"server_info":{"timezone":"Europe/Istanbul"}}"""))
            repeat(6) { server.enqueue(MockResponse().setBody(body)) }
        }
        enqueue(1)
        enqueue(2)
        val expected = MessageDigest.getInstance("SHA-256")
        val bodyDigest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) }
        actions.forEach { expected.update("$it:$bodyDigest\n".toByteArray()) }
        expected.update("Europe/Istanbul".toByteArray())
        val previous = xtream.prepare(cache.root, FetchState()).use { snapshot ->
            assertFalse(snapshot.unchanged)
            assertEquals(expected.digest().joinToString("") { "%02x".format(it) }, snapshot.state.digest)
            assertEquals(actions, snapshot.bodies.keys.toList())
            snapshot.bodies.values.forEach { downloaded ->
                assertEquals(body, downloaded.read { input, _ -> input.reader().readText() })
            }
            assertEquals(6, cache.root.listFiles()!!.size)
            cache.root.listFiles()!!.forEach { file ->
                val disk = file.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(disk.contains(secret))
                assertFalse(disk.contains("https://private.invalid"))
            }
            snapshot.state
        }
        xtream.prepare(cache.root, previous).use { assertTrue(it.unchanged) }
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellingALaterEndpointDeletesAllEarlierEncryptedBodies() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1}}"""))
        repeat(6) { server.enqueue(MockResponse().setBody(body)) }
        val cancellation = CancellationException(secret)
        var complete = 0
        val error = runCatching {
            Xtream(SourceSpec(server.url("/").toString(), true, "user", secret)).prepare(cache.root, FetchState(),
                progress = { if (it.endsWith(" MB") && ++complete == 5) throw cancellation })
        }.exceptionOrNull()
        assertSame(cancellation, error)
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }
}
