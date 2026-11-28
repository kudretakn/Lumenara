package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import dev.lumenara.design.LumenaraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A live stream whose only audio track Media3 cannot decode must not play silently. */
@UnstableApi
class AudioFallbackTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun undecodableAudioSwitchesToVlc() {
        assertTrue("Fallback fixture requires an emulator without an MPEG-L2 decoder", MediaCodecUtil.getDecoderInfos("audio/mpeg-L2", false, false).isEmpty())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val saved = runBlocking { listOf("engine", "fallback").associateWith { prefs.text(it).first() } }
        val name = "Test · MP2 audio fallback"
        val bytes = instrumentation.context.assets.open("mp2-audio-test.ts").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/list" -> MockResponse().setBody("#EXTM3U\n#EXTINF:-1 group-title=\"Test\",MP2 Kanal\n${server.url("/mp2.ts")}\n")
                    "/mp2.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(bytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            try {
                val channel = runBlocking {
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                    prefs.set("engine", "Media3")
                    prefs.set("fallback", "Açık")
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                    val source = repo.library.snapshot().single { it.name == name }
                    repo.library.browse(source.id, "LIVE", "", "", "", 10).first().single()
                }
                screen.runOnUiThread {
                    val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                    screen.activity.setContent {
                        LumenaraTheme { Playback(model, channel, listOf(channel), {}, {}, {}) }
                    }
                }
                screen.wp21AwaitNode(hasTestTag("vlc-player"))
                screen.wp21AwaitLiveFrame(channel.id)
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
