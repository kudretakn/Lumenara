package dev.streamora.tv

import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import dev.streamora.player.createPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class PlaybackTest {
    @Test
    fun media3DecodesFixtureFromHttpProvider() {
        val test = InstrumentationRegistry.getInstrumentation()
        val bytes = test.context.assets.open("streamora-test.mp4").use { it.readBytes() }
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse()
                        .setHeader("Content-Type", "video/mp4")
                        .setBody(Buffer().write(bytes))
            }
        server.start()
        val address = server.url("/sample.mp4").toString()
        val ended = CountDownLatch(1)
        var failure: String? = null
        var width = 0
        lateinit var player: ExoPlayer
        try {
            test.runOnMainSync {
                player = createPlayer(test.targetContext, true)
                player.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            failure = error.errorCodeName
                            ended.countDown()
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) {
                                width = player.videoFormat?.width ?: 0
                                ended.countDown()
                            }
                        }
                    }
                )
                player.setMediaItem(MediaItem.fromUri(address))
                player.prepare()
                player.play()
            }
            assertTrue("Video zaman aşımı", ended.await(20, TimeUnit.SECONDS))
            assertNull(failure)
            assertEquals(640, width)
        } finally {
            test.runOnMainSync { player.release() }
            server.shutdown()
        }
    }
}
