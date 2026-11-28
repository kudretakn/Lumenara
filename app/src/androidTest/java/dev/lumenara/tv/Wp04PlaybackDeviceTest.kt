package dev.lumenara.tv

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import dev.lumenara.design.LumenaraTheme
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.videolan.libvlc.util.VLCVideoLayout

class Wp04PlaybackDeviceTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun failedBPreparationCannotSaveReusedAPosition() = fixture { repo, model, rows ->
        val selected = mutableStateOf(rows[0])
        val current = AtomicReference<ExoPlayer?>()
        var failed = false
        screen.runOnUiThread {
            screen.activity.setContent {
                LumenaraTheme {
                    Video(model, selected.value, Modifier.fillMaxSize(),
                        ready = { current.set(it) }, failure = { failed = it })
                }
            }
        }
        awaitPlayer(current)
        screen.runOnIdle {
            current.get()!!.pause()
            current.get()!!.seekTo(40_000)
        }
        screen.waitUntil(10_000) { onMain { current.get()?.currentPosition == 40_000L } }
        screen.runOnIdle {
            // B still exists in Room, but preparation fails before setMediaItem.
            selected.value = rows[1].copy(source = "wp04-missing-source")
        }
        screen.waitUntil(10_000) { onMain { failed } }
        screen.runOnIdle { selected.value = rows[2] }
        screen.waitUntil(10_000) {
            onMain { current.get()?.currentMediaItem?.mediaId != null && !failed && current.get()?.playbackState == Player.STATE_READY }
        }
        screen.runOnIdle { screen.activity.setContent { } }
        // Drain earlier progress writes before checking B; an A write proves cleanup ran.
        screen.waitUntil(10_000) { runBlocking { (repo.library.mark(rows[0].id)?.position ?: 0) >= 40_000 } }
        runBlocking { repo.updateMark(rows[2].id) { it } } // Transaction barrier after queued cleanup writes.
        assertEquals(0L, runBlocking { repo.library.mark(rows[1].id)?.position ?: 0 })
    }

    @Test fun homeReturnUsesLatestPositionInsteadOfOriginalTransfer() = fixture { _, model, rows ->
        lateinit var owner: Owner
        val current = AtomicReference<ExoPlayer?>()
        screen.runOnUiThread {
            owner = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED }
            screen.activity.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LumenaraTheme {
                        Video(model, rows[0], Modifier.fillMaxSize(), initial = 10_000,
                            ready = { current.set(it) })
                    }
                }
            }
        }
        awaitPlayer(current)
        screen.runOnIdle {
            current.get()!!.pause()
            current.get()!!.seekTo(40_000)
        }
        screen.waitUntil(10_000) { onMain { current.get()?.currentPosition == 40_000L } }
        screen.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        screen.waitUntil(10_000) { current.get() == null }
        screen.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        awaitPlayer(current)
        screen.runOnIdle {
            assertEquals(40_000L, current.get()!!.currentPosition)
            assertFalse("User pause survives engine teardown", current.get()!!.playWhenReady)
        }
    }

    @Test fun vlcTransientFocusUserPauseAndHomeCleanUpScreenFlag() = fixture { _, model, rows ->
        val test = InstrumentationRegistry.getInstrumentation()
        val session = PlaybackOwnership(rows[0].id)
        lateinit var owner: Owner
        lateinit var manager: AudioManager
        lateinit var interruption: AudioFocusRequest
        screen.runOnUiThread {
            owner = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED }
            manager = checkNotNull(screen.activity.getSystemService(AudioManager::class.java))
            interruption = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { }.build()
            screen.activity.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LumenaraTheme {
                        VlcScreen(model, rows[0], 0, {}, {}, rows, null, {}, { _, _ -> }, {},
                            remember { LiveUrlCache(model) }, {}, next = {},
                            sleepTimer = remember { dev.lumenara.catalog.SleepTimer() },
                            sleepTimerVersion = 0, onSleepTimerChanged = {}, episodeEnded = {},
                            delayMs = 0, delayReady = true, changeSubtitleDelay = {}, ownership = session)
                    }
                }
            }
        }
        fun layout(): VLCVideoLayout? = findLayout(screen.activity.window.decorView)
        fun awaitAwake(awake: Boolean) {
            screen.waitUntil(30_000) { onMain { layout()?.keepScreenOn == awake } }
        }
        try {
            awaitAwake(true)
            lateinit var oldLayout: VLCVideoLayout
            screen.runOnIdle {
                oldLayout = checkNotNull(layout())
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(interruption))
            }
            awaitAwake(false)
            assertTrue(onMain { session.wantsPlay })
            screen.runOnIdle { manager.abandonAudioFocusRequest(interruption) }
            awaitAwake(true)
            screen.runOnIdle { manager.requestAudioFocus(interruption) }
            awaitAwake(false)
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
            screen.waitUntil(10_000) { onMain { !session.wantsPlay } }
            screen.runOnIdle { manager.abandonAudioFocusRequest(interruption) }
            screen.waitForIdle()
            assertFalse(onMain { checkNotNull(layout()).keepScreenOn })
            // Home while paused: teardown must clear the old view and preserve intent.
            screen.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            screen.waitUntil(10_000) { onMain { layout() == null } }
            runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { session.awaitInputRelease() } }
            assertFalse(onMain { oldLayout.keepScreenOn })
            screen.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            awaitAwake(false)
            screen.waitUntil(30_000) { onMain { session.hasBoundMedia } }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
            awaitAwake(true)
            screen.runOnIdle {
                oldLayout = checkNotNull(layout())
                screen.activity.setContent { }
            }
            screen.waitUntil(10_000) { onMain { !oldLayout.keepScreenOn } }
            runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { session.awaitInputRelease() } }
        } finally {
            screen.runOnUiThread { manager.abandonAudioFocusRequest(interruption) }
        }
    }

    private fun findLayout(view: View): VLCVideoLayout? {
        if (view is VLCVideoLayout) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findLayout(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun awaitPlayer(current: AtomicReference<ExoPlayer?>) {
        screen.waitUntil(30_000) { onMain { current.get()?.playbackState == Player.STATE_READY } }
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun fixture(block: (Repository, LibraryModel, List<Item>) -> Unit) {
        val test = InstrumentationRegistry.getInstrumentation()
        val repo = Repository.get(test.targetContext)
        val name = "Test · WP04 ${System.nanoTime()}"
        val bytes = test.context.assets.open("audio-subtitle-test.mp4").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/list") return MockResponse().setBody(buildString {
                        appendLine("#EXTM3U")
                        repeat(3) { index ->
                            appendLine("#EXTINF:-1 group-title=\"Filmler\",WP04 $index")
                            appendLine(server.url("/movie/$index.mp4"))
                        }
                    })
                    val range = request.getHeader("Range")?.substringAfter("bytes=")
                    val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
                    val end = (range?.substringAfter('-')?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                    if (start >= bytes.size || end < start) return MockResponse().setResponseCode(416)
                    return MockResponse().setResponseCode(if (range != null) 206 else 200)
                        .setHeader("Content-Type", "video/mp4")
                        .setHeader("Accept-Ranges", "bytes")
                        .apply { if (range != null) setHeader("Content-Range", "bytes $start-$end/${bytes.size}") }
                        .setBody(Buffer().write(bytes, start, end - start + 1))
                }
            }
            server.start()
            var sourceId: String? = null
            try {
                val rows = runBlocking {
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                    val source = repo.library.snapshot().single { it.name == name }
                    sourceId = source.id
                    repo.browsePage(source.id, "MOVIE", "", "", "", 80, 0).first()
                }
                assertEquals(3, rows.size)
                lateinit var model: LibraryModel
                screen.runOnUiThread { model = ViewModelProvider(screen.activity)[LibraryModel::class.java] }
                block(repo, model, rows)
            } finally {
                screen.runOnUiThread { screen.activity.setContent { } }
                runBlocking { sourceId?.let { repo.remove(it) } }
            }
        }
    }
}
