package dev.lumenara.tv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.lumenara.data.Repository
import dev.lumenara.data.SourceSpec
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Device captures use the same external-files and Download locations as CinemaJourneyTest. */
class SettingsSetupJourneyTest {
    val screen = createAndroidComposeRule<MainActivity>()
    private val sourceName = "Test · Ayarlar · ${java.util.UUID.randomUUID()}"
    private val fixture = TestRule { test, _ ->
        object : Statement() {
            override fun evaluate() {
                val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
                MockWebServer().use { server ->
                    server.start()
                    try {
                        runBlocking {
                            server.enqueue(MockResponse().setBody(
                                "#EXTM3U\n#EXTINF:-1,Örnek Kanal\n${server.url("/live.ts")}\n"
                            ))
                            repo.add(sourceName, SourceSpec(server.url("/list").toString(), true))
                        }
                        test.evaluate()
                    } finally {
                        runBlocking {
                            repo.library.snapshot().filter { it.name == sourceName }.forEach { repo.remove(it.id) }
                        }
                    }
                }
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(screen)

    private fun awaitTag(tag: String) {
        screen.waitUntil(30_000) { screen.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun select(matcher: SemanticsMatcher) {
        screen.onNode(matcher).assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
        screen.waitUntil(30_000) {
            screen.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        screen.ok()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val readyAt = android.os.SystemClock.uptimeMillis() + 1300
        screen.waitUntil(30_000) { android.os.SystemClock.uptimeMillis() >= readyAt }
        val file = File(context.getExternalFilesDir(null), "screens/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        assertTrue(device.takeScreenshot(file))
        device.executeShellCommand("mkdir -p /sdcard/Download/lumenara-ui")
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/lumenara-ui/${file.name}")
    }

    @Test fun settingsGroupsVersionAndFocusRestore() {
        awaitTag("hub")
        screen.openHubCard("Ayarlar")
        screen.openSettingsGroup("Oynatıcı")
        val choice = hasText("Kare hızını eşle", substring = true)
        screen.onNodeWithTag("settings-list").performScrollToNode(choice)
        screen.onNode(choice).performSemanticsAction(SemanticsActions.RequestFocus)
        capture("settings")
        screen.openSettingsGroup("Hakkında")
        screen.onNodeWithTag("settings-list").performScrollToNode(hasText("Lumenara ${BuildConfig.VERSION_NAME}"))
        screen.onNodeWithText("Lumenara ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        screen.onNodeWithText("Türkçe   ·   Antrasit tema   ·   Lumenara 0.1").assertDoesNotExist()
        screen.openSettingsGroup("Kaynaklar")
        select(hasText("IPTV Kaynakları"))
        awaitTag("setup-screen")
        screen.back()
        screen.waitUntil(5_000) {
            screen.onAllNodes(hasText("IPTV Kaynakları") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun welcomeFormAdvancedAndBack() {
        awaitTag("hub")
        screen.openHubCard("Ayarlar")
        screen.openSettingsGroup("Kaynaklar")
        select(hasText("IPTV Kaynakları"))
        awaitTag("setup-screen")
        screen.onNodeWithText("Hoş geldiniz").assertIsDisplayed()
        screen.onNodeWithText("Liste adresi gir").performSemanticsAction(SemanticsActions.RequestFocus)
        capture("setup")
        select(hasText("Liste adresi gir"))
        screen.onNodeWithTag("source-setup").performScrollToNode(hasTestTag("setup-advanced"))
        screen.onNodeWithText("HTTP uyumluluğu · Kapalı").assertDoesNotExist()
        select(hasTestTag("setup-advanced"))
        screen.onNodeWithTag("source-setup").performScrollToNode(hasText("HTTP uyumluluğu · Kapalı"))
        screen.onNodeWithText("HTTP uyumluluğu · Kapalı").assertIsDisplayed()
        screen.back()
        screen.onNodeWithText("Hoş geldiniz").assertIsDisplayed()
        screen.back()
        awaitTag("settings-list")
    }
}
