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
            if (!device.wait(Until.hasObject(By.res(Pattern.compile("hub|setup-screen"))), timeout)) skip("Hub/setup unavailable before profile collection")
            if (device.hasObject(By.res("setup-screen"))) addFixture(server.url("/list").toString())

            baselineProfile.collect(
                packageName = packageName,
                includeInStartupProfile = true,
            ) {
                killProcess()
                pressHome()
                startActivityAndWait()
                if (optional(By.res("hub"), "Hub after startup") == null) return@collect

                optional(By.res(Pattern.compile("hub-.*TV")), "Live navigation")?.click()
                val liveCategory = firstCategory()
                if (liveCategory != null) {
                    liveCategory.click()
                    optional(By.res("live-channels"), "Live channel grid")
                    // DPAD scroll follows TV focus and never activates a channel.
                    repeat(16) { device.pressDPadDown(); device.waitForIdle() }
                } else skip("No live categories; skipping channel grid")
                returnToHub()

                optional(By.res(Pattern.compile("hub-.*Filmler")), "Movies navigation")?.click()
                val movieCategory = firstCategory()
                if (movieCategory != null) {
                    movieCategory.click()
                    optional(By.res("poster-grid"), "Movie poster grid")
                    val poster = device.wait(Until.findObject(
                        By.res("poster-grid").hasDescendant(By.clickable(true).desc(Pattern.compile(".+")))
                    ), timeout)?.findObject(By.clickable(true).desc(Pattern.compile(".+")))
                    if (poster != null) {
                        poster.click()
                        optional(By.res("details-content"), "Movie details")
                    } else {
                        skip("Empty movie category; skipping details")
                    }
                } else skip("No movie categories; skipping details")
                returnToHub()

                optional(By.res(Pattern.compile("hub-.*Ara")), "Search navigation")?.click()
                optional(By.res("search-input"), "Search input")?.text = "ge"
                // Search is debounced; wait for matching fixture/results composition.
                android.os.SystemClock.sleep(750)
                optional(By.res("search-results"), "Search results")
                device.waitForIdle()
            }
        }
    }

    private fun optional(selector: BySelector, description: String): UiObject2? =
        device.wait(Until.findObject(selector), timeout).also { if (it == null) skip("$description unavailable; continuing") }

    private fun firstCategory(): UiObject2? {
        if (optional(By.res("category-grid"), "Category grid") == null) return null
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
        optional(By.res("hub"), "Return to Hub")
    }

    private fun reveal(selector: BySelector): UiObject2? {
        repeat(10) {
            device.findObject(selector)?.let { return it }
            val form = device.findObject(By.res("source-setup")) ?: return null
            form.scroll(Direction.DOWN, 0.6f)
            device.wait(Until.hasObject(selector), 1_000L)
        }
        return optional(selector, "Setup control")
    }

    private fun addFixture(address: String) {
        if (optional(By.res("setup-add-m3u"), "M3U setup action")?.let { runCatching { it.click() }.isSuccess } != true) return
        (reveal(By.res("setup-name")) ?: return).text = "Baseline Profile fixture"
        (reveal(By.res("setup-address")) ?: return).text = address
        (reveal(By.res("setup-advanced")) ?: return).click()
        (reveal(By.res("setup-http-compat")) ?: return).click()
        (reveal(By.res("setup-submit")) ?: return).click()
        optional(By.res("hub"), "Return to Hub")
    }

    private fun skip(reason: String) { Log.i("BaselineProfileGenerator", reason) }
}
