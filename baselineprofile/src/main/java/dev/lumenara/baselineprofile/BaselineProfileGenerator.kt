package dev.lumenara.baselineprofile

import android.util.Log
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only through :app:generateBaselineProfile, against the non-debuggable app. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baselineProfile = BaselineProfileRule()
    private val packageName = "dev.lumenara.tv"
    private val timeout = 30_000L
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun startupAndBrowse() {
        // Like LibraryJourneyTest, serve a local M3U and enable HTTP through Setup.
        // Extra channels exercise several rows without starting any network playback.
        MockWebServer().use { server ->
            server.start()
            val playlist = buildString {
                append("#EXTM3U\n")
                repeat(48) { index ->
                    append("#EXTINF:-1 group-title=\"Spor\",Test Kanalı $index\n")
                    append("${server.url("/live/$index")}\n")
                }
                append("#EXTINF:-1 group-title=\"Filmler\",Gece Treni\n")
                append("${server.url("/movie/1.mp4")}\n")
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/list") MockResponse().setBody(playlist)
                    else MockResponse().setResponseCode(404)
            }
            device.executeShellCommand("am force-stop $packageName")
            device.executeShellCommand("am start -W -n $packageName/.MainActivity")
            check(device.wait(Until.hasObject(By.res(Pattern.compile("hub|setup-screen"))), timeout)) {
                "Neither Hub nor setup appeared"
            }
            if (device.hasObject(By.res("setup-screen"))) addFixture(server.url("/list").toString())
            required(By.res("hub"))

            baselineProfile.collect(
                packageName = packageName,
                includeInStartupProfile = true,
            ) {
                killProcess()
                pressHome()
                startActivityAndWait()
                required(By.res("hub"))

                required(By.res("hub-Canlı TV")).click()
                val liveCategory = firstCategory()
                if (liveCategory != null) {
                    liveCategory.click()
                    required(By.res("live-channels"))
                    // DPAD scroll follows TV focus and never activates a channel.
                    repeat(16) { device.pressDPadDown(); device.waitForIdle() }
                } else skip("No live categories; skipping channel grid")
                returnToHub()

                required(By.res("hub-Filmler")).click()
                val movieCategory = firstCategory()
                if (movieCategory != null) {
                    movieCategory.click()
                    required(By.res("poster-grid"))
                    val poster = device.wait(Until.findObject(
                        By.res("poster-grid").hasDescendant(By.clickable(true).desc(Pattern.compile(".+")))
                    ), timeout)?.findObject(By.clickable(true).desc(Pattern.compile(".+")))
                    if (poster != null) {
                        poster.click()
                        required(By.res("details-content"))
                    } else {
                        skip("Empty movie category; skipping details")
                    }
                } else skip("No movie categories; skipping details")
                returnToHub()

                required(By.res("hub-Ara")).click()
                required(By.res("search-input")).text = "ge"
                // Search is debounced; wait for matching fixture/results composition.
                android.os.SystemClock.sleep(750)
                required(By.res("search-results"))
                device.waitForIdle()
            }
        }
    }

    private fun required(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing UI: $selector" }

    private fun firstCategory(): UiObject2? {
        required(By.res("category-grid"))
        val card = By.res(Pattern.compile("category-.+")).clickable(true)
        return device.wait(Until.findObject(By.res("category-grid").hasDescendant(card)), timeout)
            ?.findObject(card)
    }

    private fun returnToHub() {
        repeat(5) {
            if (device.hasObject(By.res("hub"))) return
            device.pressBack()
            device.waitForIdle()
        }
        required(By.res("hub"))
    }

    private fun reveal(selector: BySelector): UiObject2 {
        repeat(10) {
            device.findObject(selector)?.let { return it }
            required(By.res("source-setup")).scroll(Direction.DOWN, 0.6f)
            device.waitForIdle()
        }
        return required(selector)
    }

    private fun addFixture(address: String) {
        required(By.text("Liste adresi gir")).click()
        reveal(By.desc("Liste adı")).text = "Baseline Profile fixture"
        reveal(By.desc("Liste adresi")).text = address
        reveal(By.res("setup-advanced")).click()
        reveal(By.text("HTTP uyumluluğu · Kapalı")).click()
        reveal(By.text("Bağlantıyı sına ve ekle")).click()
        required(By.res("hub"))
    }

    private fun skip(reason: String) { Log.i("BaselineProfileGenerator", reason) }
}
