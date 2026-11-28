package dev.lumenara.tv

import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.vlc.VlcInput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.videolan.libvlc.*
import org.videolan.libvlc.util.VLCVideoLayout

class VlcTest {
    @Test
    fun nativeEngineReadsSeekableFixtureWithoutNetworkCredentials() {
        val diagnostics = FixtureDiagnostics()
        // A coroutine timeout cannot interrupt JNI, runOnMainSync, or a FUSE read. Keep
        // the deadline on the test thread, outside *all* resource cleanup/finally blocks.
        val work = FutureTask<Unit> { readFixture(diagnostics) }
        val worker = thread(name = "lumenara-vlc-fixture", isDaemon = true) { work.run() }
        try {
            work.get(60, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            val message = "VLC fixture exceeded 60s including setup/teardown: ${diagnostics.describe()}\n" +
                Thread.getAllStackTraces().entries.filter { (t, _) ->
                    t === worker || t.name == "main" || t.name.startsWith("lumenara-vlc") ||
                        t.name == "lumenara-ts"
                }.joinToString("\n") { (t, stack) ->
                    "thread=${t.name} state=${t.state}\n" + stack.joinToString("\n") { "  at $it" }
                }
            // Keep every stack line in logcat even when the combined message exceeds
            // Android's per-entry log limit.
            message.lineSequence().forEach { Log.e("LumenaraVlcTest", it) }
            // Do not release/close from this thread while stop may still own the fd.
            // The daemon retains ownership and may finish cleanup if JNI returns later.
            throw AssertionError(message, error)
        } catch (error: ExecutionException) {
            throw AssertionError("VLC fixture failed: ${diagnostics.describe()}", error.cause ?: error)
        }
    }

    private fun readFixture(diagnostics: FixtureDiagnostics) {
        val test = InstrumentationRegistry.getInstrumentation()
        diagnostics.phase = "read asset"
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
            diagnostics.phase = "start activity"
            val activity =
                test.startActivitySync(
                    Intent(test.targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ) as MainActivity
            diagnostics.phase = "open seekable input"
            VlcInput.open(test.targetContext, server.url("/sample.mp4").toString(), true).use {
                input ->
                diagnostics.input = input
                diagnostics.phase = "create LibVLC"
                val engine =
                    LibVLC(
                        test.targetContext,
                        arrayListOf("--quiet"),
                    )
                diagnostics.phase = "create MediaPlayer"
                val player = MediaPlayer(engine)
                val finished = CountDownLatch(1)
                var error = false
                try {
                    diagnostics.phase = "install media"
                    val media = Media(engine, input.descriptor.fileDescriptor)
                    try {
                        media.addOption(":demux=${input.demux}")
                        player.media = media
                    } finally { media.release() }
                    diagnostics.phase = "attach and play on Main"
                    test.runOnMainSync {
                        player.setEventListener {
                            diagnostics.onEvent(it)
                            if (it.type == MediaPlayer.Event.EncounteredError) {
                                error = true
                                finished.countDown()
                            }
                            if (it.type == MediaPlayer.Event.EndReached) finished.countDown()
                        }
                        val view = VLCVideoLayout(activity)
                        activity.setContentView(view)
                        player.attachViews(view, null, true, false)
                        // Serialize with libvlcjni's surface-created play callback.
                        player.play()
                    }
                    diagnostics.phase = "await EndReached"
                    val ended = finished.await(30, TimeUnit.SECONDS)
                    assertTrue("No terminal VLC event within 30s: ${diagnostics.describe()}", ended)
                    assertFalse("VLC EncounteredError: ${diagnostics.describe()}", error)
                    assertTrue("Expected Range probe and media reads: ${diagnostics.describe()}", server.requestCount >= 2)
                } catch (failure: Throwable) {
                    diagnostics.failure = failure.javaClass.simpleName
                    throw failure
                } finally {
                    diagnostics.phase = "detach listener on Main"
                    test.runOnMainSync { player.setEventListener(null) }
                    diagnostics.phase = "cancel input"
                    input.cancel()
                    diagnostics.phase = "native stop"
                    player.stop()
                    diagnostics.phase = "clear media"
                    player.media = null
                    diagnostics.phase = "detach views on Main"
                    test.runOnMainSync {
                        player.detachViews()
                        activity.finish()
                    }
                    diagnostics.phase = "release MediaPlayer"
                    player.release()
                    diagnostics.phase = "release LibVLC"
                    engine.release()
                    diagnostics.phase = "close input descriptor"
                }
            }
            diagnostics.phase = "shutdown server"
        }
    }

    private class FixtureDiagnostics {
        @Volatile var phase = "setup"
        @Volatile var input: VlcInput? = null
        @Volatile var failure = "none"
        @Volatile private var state = "not started"
        @Volatile private var lastEvent = -1
        @Volatile private var time = 0L

        fun onEvent(event: MediaPlayer.Event) {
            lastEvent = event.type
            state = when (event.type) {
                MediaPlayer.Event.Opening -> "Opening"
                MediaPlayer.Event.Playing -> "Playing"
                MediaPlayer.Event.Paused -> "Paused"
                MediaPlayer.Event.Stopped -> "Stopped"
                MediaPlayer.Event.EndReached -> "EndReached"
                MediaPlayer.Event.EncounteredError -> "EncounteredError"
                else -> state
            }
            if (event.type == MediaPlayer.Event.TimeChanged) time = event.timeChanged
        }

        // Event-derived state only; querying player.state/time here could itself hang.
        fun describe() = "phase=$phase engineState=$state lastNativeEvent=" +
            "${if (lastEvent < 0) "none" else "0x${lastEvent.toString(16)}"} lastTimeMs=$time " +
            "failure=$failure ${input?.diagnosticState() ?: "input=none"}"
    }
}
