package dev.lumenara.baselineprofile

import android.os.Build
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

/** Opt-in API36 emulator tooling. Every requested journey must finish or generation fails. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baselineProfile = BaselineProfileRule()
    private val packageName = "dev.lumenara.tv"
    private val timeout = 30_000L
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun startupAndBrowse() {
        requireProfileEmulator()
        // A fresh disposable AVD makes fixture ownership explicit, without clearing app data.
        // Existing catalogues are rejected rather than silently producing partial journeys.
        MockWebServer().use { server ->
            server.start()
            val playlist = buildString {
                append("#EXTM3U\n")
                repeat(48) { index ->
                    append("#EXTINF:-1 group-title=\"Wp20 Spor\",Wp20 Kanal ${index.toString().padStart(2, '0')}\n")
                    append("${server.url("/live/$index")}\n")
                }
                append("#EXTINF:-1 group-title=\"Wp20 Filmler\",Wp20 Gece Treni\n")
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
                "WP20 Hub/setup unavailable before collection"
            }
            check(device.hasObject(By.res("setup-screen"))) {
                "WP20 requires a fresh disposable API36 TV AVD with no existing release catalog"
            }
            addFixture(server.url("/list").toString())

            // Distinct output prefixes keep browse methods out of the startup layout profile.
            // Both collections are in one test: any missing journey fails the entire task.
            baselineProfile.collect(
                packageName = packageName,
                outputFilePrefix = "Wp20Startup",
                includeInStartupProfile = true,
                strictStability = true,
            ) {
                killProcess()
                pressHome()
                startActivityAndWait()
                required(By.res("hub"), "Hub after cold startup")
                required(By.res("hub-Canlı TV"), "Hub navigation")
            }
            baselineProfile.collect(
                packageName = packageName,
                outputFilePrefix = "Wp20Browse",
                includeInStartupProfile = false,
                strictStability = true,
            ) {
                killProcess()
                pressHome()
                startActivityAndWait()
                required(By.res("hub"), "Hub before browsing")
                required(By.res("hub-Canlı TV"), "Live navigation").click()
                category("Wp20 Spor").click()
                required(By.res("live-channels"), "Live grid")
                val liveFocus = By.res(Pattern.compile("live-channel-.+")).focused(true)
                val initialFocus = required(liveFocus, "Initial live channel focus").contentDescription
                repeat(16) { device.pressDPadDown(); device.waitForIdle() }
                check(required(liveFocus, "Live focus after scrolling").contentDescription != initialFocus) {
                    "WP20 live DPAD scrolling did not move focus"
                }
                returnToHub()

                required(By.res("hub-Filmler"), "Movies navigation").click()
                category("Wp20 Filmler").click()
                required(By.res("poster-grid"), "Movie grid")
                // The slot tag is on the outer Column; the clickable focus card owns the description.
                required(By.clickable(true).desc("Wp20 Gece Treni"), "Fixture poster").click()
                required(By.res("details-content"), "Movie details")
                returnToHub()

                required(By.res("hub-Ara"), "Search navigation").click()
                required(By.res("search-input"), "Search input").text = "Wp20 Gece"
                required(By.res("search-results"), "Search results")
                required(By.res(Pattern.compile("search-slot-.+")).desc("Wp20 Gece Treni"), "Populated search result")
                returnToHub()
            }
        }
    }

    private fun requireProfileEmulator() {
        val serial = InstrumentationRegistry.getArguments().getString("wp20.profileSerial").orEmpty()
        check(Regex("emulator-[0-9]+").matches(serial)) { "WP20 missing host-gated emulator serial" }
        check(Build.VERSION.SDK_INT == 36 && Build.SUPPORTED_ABIS.contains("x86_64")) {
            "WP20 profiles require API36 x86_64"
        }
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
            "WP20 profile generation is forbidden on physical devices"
        }
        check(InstrumentationRegistry.getInstrumentation().context.packageManager
            .hasSystemFeature("android.software.leanback")) { "WP20 requires Android TV" }
    }

    private fun required(selector: BySelector, description: String): UiObject2 =
        device.wait(Until.findObject(selector), timeout)
            ?: error("WP20 missing required journey: $description")

    private fun category(name: String): UiObject2 {
        required(By.res("category-grid"), "Category grid")
        return required(By.res("category-$name").clickable(true), "Fixture category")
    }

    private fun returnToHub() {
        repeat(5) {
            if (device.hasObject(By.res("hub"))) return
            device.pressBack()
            device.waitForIdle()
        }
        required(By.res("hub"), "Return to Hub")
    }

    private fun reveal(selector: BySelector): UiObject2 {
        repeat(10) {
            device.findObject(selector)?.let { return it }
            required(By.res("source-setup"), "Setup form").scroll(Direction.DOWN, 0.6f)
            device.wait(Until.hasObject(selector), 1_000L)
        }
        return required(selector, "Setup control")
    }

    private fun addFixture(address: String) {
        required(By.res("setup-add-m3u"), "M3U setup action").click()
        reveal(By.res("setup-name")).text = "Wp20 Profile fixture"
        reveal(By.res("setup-address")).text = address
        reveal(By.res("setup-advanced")).click()
        reveal(By.res("setup-http-compat")).click()
        reveal(By.res("setup-submit")).click()
        required(By.res("hub"), "Hub after fixture import")
    }
}
