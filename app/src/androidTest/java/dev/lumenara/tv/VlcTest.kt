package dev.lumenara.tv

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.vlc.VlcInput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.videolan.libvlc.*
import org.videolan.libvlc.util.VLCVideoLayout

class VlcTest {
    @Test
    fun nativeEngineReadsSeekableFixtureWithoutNetworkCredentials() {
        val test = InstrumentationRegistry.getInstrumentation()
        val bytes = test.context.assets.open("lumenara-test.mp4").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val range = request.getHeader("Range")?.removePrefix("bytes=")?.split('-')
                        val start = range?.firstOrNull()?.toIntOrNull() ?: 0
                        val end = range?.lastOrNull()?.toIntOrNull() ?: bytes.lastIndex
                        return MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                            .setBody(Buffer().write(bytes, start, end - start + 1))
                    }
                }
            server.start()
            val activity =
                test.startActivitySync(
                    Intent(test.targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ) as MainActivity
            VlcInput.open(test.targetContext, server.url("/sample.mp4").toString(), true).use {
                input ->
                val engine =
                    LibVLC(
                        test.targetContext,
                        arrayListOf("--quiet"),
                    )
                val player = MediaPlayer(engine)
                val finished = CountDownLatch(1)
                var error = false
                try {
                    test.runOnMainSync {
                        val view = VLCVideoLayout(activity)
                        activity.setContentView(view)
                        player.attachViews(view, null, true, false)
                    }
                    val media = Media(engine, input.descriptor.fileDescriptor)
                    media.addOption(":demux=${input.demux}")
                    player.media = media
                    media.release()
                    player.setEventListener {
                        if (it.type == MediaPlayer.Event.EncounteredError) {
                            error = true
                            finished.countDown()
                        }
                        if (it.type == MediaPlayer.Event.EndReached) finished.countDown()
                    }
                    player.play()
                    assertTrue(finished.await(30, TimeUnit.SECONDS))
                    assertFalse(error)
                    assertTrue(server.requestCount >= 2)
                } finally {
                    player.stop()
                    test.runOnMainSync {
                        player.detachViews()
                        activity.finish()
                    }
                    player.release()
                    engine.release()
                }
            }
        }
    }
}
