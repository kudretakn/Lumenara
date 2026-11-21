package dev.lumenara.baselineprofile

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only through :app:generateBaselineProfile, against the non-debuggable app. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baselineProfile = BaselineProfileRule()
    private val packageName = "dev.lumenara.tv"
    private val timeout = 30_000L
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val server = MockWebServer()
    private val artworkServer = MockWebServer()
    private val images = AtomicInteger()
    private val streams = AtomicInteger()

    @Before
    fun prepareCatalog() {
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
            "Baseline profiles must run on the local emulator, never the TV."
        }
        server.start()
        // Repository deliberately drops non-HTTPS logos even for HTTP-compatible sources.
        // Trust this test certificate only in the target's nonMinifiedRelease resources.
        val password = "baseline-fixture".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            instrumentation.context.assets.open("baseline-artwork.p12").use { load(it, password) }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, password)
        }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        artworkServer.useHttps(tls.socketFactory, false)
        artworkServer.start()
        val playlist = playlist()
        val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff425b78.toInt())
        val poster = ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
        bitmap.recycle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/list" -> MockResponse()
                    .setHeader("Content-Type", "audio/x-mpegurl").setBody(playlist)
                else -> {
                    streams.incrementAndGet()
                    MockResponse().setResponseCode(404)
                }
            }
        }
        artworkServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.requestUrl?.encodedPath.orEmpty().startsWith("/images/") -> {
                    images.incrementAndGet()
                    MockResponse().setHeader("Content-Type", "image/png")
                        .setBody(Buffer().write(poster))
                }
                else -> {
                    streams.incrementAndGet()
                    MockResponse().setResponseCode(404)
                }
            }
        }
        // Each test owns fresh data and a fresh server port; no stale fixture can pass.
        check(device.executeShellCommand("pm clear $packageName").trim() == "Success") {
            "Could not clear the emulator app before importing the baseline fixture."
        }
        device.executeShellCommand("am start -W -n $packageName/.MainActivity")
        requireNode(By.res("setup-screen"), "Clean-install setup")
        addFixture(server.url("/list").toString())

        // Hub shelves require history. This receiver exists ONLY in nonMinifiedRelease;
        // it seeds history, never the source/catalog (which must go through Setup).
        val result = device.executeShellCommand(
            "am broadcast --receiver-foreground -n $packageName/.BaselineProfileFixtureReceiver " +
                "-a dev.lumenara.tv.SEED_BASELINE_HISTORY"
        )
        if (!result.contains("fixture-history-ready")) fail("Could not seed Hub history: $result")
        requireNode(By.res("hub"), "Hub after fixture import")
    }

    @After
    fun stopFixture() {
        try {
            check(streams.get() == 0) { "Browsing unexpectedly requested ${streams.get()} streams" }
        } finally {
            try { artworkServer.shutdown() } finally { server.shutdown() }
        }
    }

    @Test
    fun startup() {
        baselineProfile.collect(packageName = packageName, includeInStartupProfile = true) {
            killProcess()
            pressHome()
            startActivityAndWait()
            requireNode(By.res("hub"), "Hub after cold startup")
            requireNode(By.res("hub-Canlı TV").focused(true), "Initial Hub focus")
            requireNode(By.res("channel-shelf-Son kanal"), "Populated Hub on startup")
            device.waitForIdle()
        }
    }

    @Test
    fun browseCatalog() {
        baselineProfile.collect(packageName = packageName, includeInStartupProfile = false) {
            killProcess()
            pressHome()
            startActivityAndWait()
            requireNode(By.res("hub"), "Hub before browsing")
            browseHubShelves()

            openHub("Canlı TV")
            openCategory("BP Spor")
            requireNode(By.res("live-channels"), "Live channel grid")
            requireNode(By.res("live-slot-0").hasDescendant(By.focused(true)), "First live channel focus")
            down(8)
            right(3)
            // Four dense columns: prove we reached a row beyond the initial six.
            requireNode(By.res("live-slot-35").hasDescendant(By.focused(true)), "Scrolled live channel 35")
            returnToHub()

            openHub("Filmler")
            openCategory("BP Filmler A")
            requireNode(By.res("poster-grid"), "Movie poster grid")
            requireNode(By.res("poster-slot-0").hasDescendant(By.focused(true)), "First movie focus")
            down(8)
            right(3)
            requireNode(By.res("poster-slot-67").hasDescendant(By.focused(true)), "Scrolled movie 67")
            device.pressDPadCenter()
            requireNode(By.res("details-content"), "Movie details")
            requireNode(By.res("details-play").focused(true), "Movie details ready (without playing)")
            device.waitForIdle()
            back()
            requireNode(By.res("poster-grid"), "Movie grid after details")
            returnToHub()

            openHub("Diziler")
            openCategory("BP Diziler")
            requireNode(By.res("poster-grid"), "Series grid")
            requireNode(By.res("poster-slot-0").hasDescendant(By.focused(true)), "First series focus")
            device.pressDPadCenter()
            requireNode(By.res("series-page"), "Series details")
            reveal("details-content", "season-1")
            activate("season-1")
            requireNode(By.res("season-page"), "Season episode page")
            requireNode(By.res("episode-list").hasDescendant(By.focused(true)), "Loaded episodes")
            right(6)
            back()
            requireNode(By.res("series-page"), "Series details after episodes")
            back()
            requireNode(By.res("poster-grid"), "Series grid after details")
            returnToHub()

            openHub("Ara")
            setFieldText("search-input", "BP Film A")
            // The container exists before debounce/query completion; require a real result.
            requireNode(By.res("search-results").hasDescendant(
                By.desc(Pattern.compile("BP Film A .+"))
            ), "Search results for BP Film A")
            device.waitForIdle()
            // Wait while artwork is visible, not after returning to a different screen.
            // UI resource IDs also exist when Repository supplies no image model.
            requireArtwork()
            returnToHub()

            openHub("Ayarlar")
            requireNode(By.res("settings-list"), "Settings content")
            device.waitForIdle()
            returnToHub()
            if (images.get() == 0) fail("No fixture HTTPS artwork requested; Coil was not exercised")
        }
    }

    private fun playlist(): String = buildString {
        append("#EXTM3U\n")
        fun entry(group: String, title: String, path: String) {
            // tvg-logo is not resolved against the playlist base; it must be absolute HTTPS.
            append("#EXTINF:-1 group-title=\"$group\" tvg-logo=\"${artworkServer.url("/images/$path.png")}\",$title\n")
            append("${server.url("/$path")}\n")
        }
        listOf("Spor", "Haber", "Muzik").forEachIndexed { category, name ->
            repeat(96) { index ->
                entry("BP $name", "BP $name Kanal %03d".format(index), "live/$category/$index")
            }
        }
        listOf("A", "B", "C").forEach { category ->
            repeat(96) { index ->
                entry("BP Filmler $category", "BP Film $category %03d".format(index), "movie/$category/$index.mp4")
            }
        }
        repeat(2) { season ->
            repeat(8) { episode ->
                entry("BP Diziler", "BP Yolculuk S${season + 1}E${episode + 1}", "series/$season/$episode.mp4")
            }
        }
    }

    private fun requireArtwork() {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (images.get() > 0) return
            SystemClock.sleep(100)
        }
        fail("No local artwork requested; Coil was not exercised. " +
            "Expected absolute tvg-logo URLs under ${artworkServer.url("/images/")}; " +
            "HTTPS requests=${artworkServer.requestCount}, HTTP requests=${server.requestCount}. " +
            "Repository keeps only https:// logos regardless of source HTTP opt-in; " +
            "verify the nonMinifiedRelease localhost certificate trust and TLS handshake")
    }

    private fun addFixture(address: String) {
        // Match LibraryJourneyTest: focus + remote OK, not a touchscreen tap on TV Surface.
        activate("setup-add-m3u")
        requireNode(By.text("Yöntemi değiştir"), "M3U form after Liste adresi gir")
        setFieldText("setup-name", "Baseline Profile fixture", "source-setup")
        setFieldText("setup-address", address, "source-setup")
        reveal("source-setup", "setup-advanced")
        activate("setup-advanced")
        reveal("source-setup", "setup-http-compat")
        activate("setup-http-compat")
        requireNode(By.text("HTTP uyumluluğu · Açık"), "HTTP permission enabled")
        reveal("source-setup", "setup-submit")
        activate("setup-submit")
        requireNode(By.res("hub"), "Hub after Bağlantıyı sına ve ekle (fixture import)")
    }

    private fun browseHubShelves() {
        requireNode(By.res("hub-Canlı TV").focused(true), "Hub navigation focus")
        requireNode(By.res("channel-shelf-Son kanal").hasDescendant(By.clickable(true)), "Loaded recent channels")
        down(2)
        requireNode(By.res("channel-shelf-Son kanal").hasDescendant(By.focused(true)), "Recent channel shelf focus")
        right(8)
        down(1)
        requireNode(By.res("shelf-Kaldığın yerden devam et").hasDescendant(By.focused(true)), "Continue watching shelf focus")
        right(8)
        // Restore the navigation viewport without activating a shelf/stream.
        repeat(5) {
            requireNode(By.res("hub"), "Hub scroll reset").scroll(Direction.UP, 0.8f)
            device.waitForIdle()
        }
    }

    private fun openHub(name: String) {
        requireNode(By.res("hub"), "Hub before $name")
        reveal("hub", "hub-$name", Direction.UP)
        activate("hub-$name")
        requireNode(By.res("section-$name"), "$name section")
    }

    private fun openCategory(name: String) {
        requireNode(By.res("category-grid"), "Categories before $name")
        reveal("category-grid", "category-$name")
        activate("category-$name")
        if (!device.wait(Until.gone(By.res("category-grid")), timeout)) fail("Category did not open: $name")
        device.waitForIdle()
    }

    private fun returnToHub() {
        repeat(6) {
            if (device.hasObject(By.res("hub"))) return
            back()
        }
        requireNode(By.res("hub"), "Return to Hub")
    }

    private fun back() { device.pressBack(); device.waitForIdle() }
    private fun down(count: Int) { repeat(count) { device.pressDPadDown(); device.waitForIdle() } }
    private fun right(count: Int) { repeat(count) { device.pressDPadRight(); device.waitForIdle() } }

    private fun requireNode(selector: BySelector, description: String): UiObject2 =
        device.wait(Until.findObject(selector), timeout) ?: fail("$description unavailable: $selector")

    private fun setFieldText(tag: String, value: String, container: String? = null) {
        if (container != null) reveal(container, tag)
        requireNode(By.res(tag).enabled(true), "Editable field $tag")
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: fail("No active window for $tag")
        // LibraryJourneyTest uses hasSetTextAction(), not class + contentDescription.
        // Compose may export the description separately from the editable node.
        val field = try { findEditable(root, tag) } finally { root.recycle() }
            ?: fail("No ACTION_SET_TEXT node in $tag")
        try {
            if (!field.isFocused && !field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                fail("Could not focus editable field $tag")
            }
            // Focus can open the TV IME even when setting text without clicking/OK.
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                fail("ACTION_SET_TEXT rejected for $tag")
            }
        } finally { field.recycle() }
        val ownText = By.res(tag).text(value)
        val childText = By.res(tag).hasDescendant(By.text(value))
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.hasObject(ownText) || device.hasObject(childText)) {
                dismissImeIfShown()
                return
            }
            SystemClock.sleep(100)
        }
        fail("Text replacement did not reach $tag")
    }

    private fun dismissImeIfShown() {
        device.waitForIdle()
        val deadline = SystemClock.uptimeMillis() + timeout
        var backPressed = false
        var hiddenSince: Long? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val state = Regex("""\bmInputShown=(true|false)\b""")
                .find(device.executeShellCommand("dumpsys input_method"))?.groupValues?.get(1)
                ?: fail("Could not determine IME visibility from dumpsys input_method")
            if (state == "true") {
                hiddenSince = null
                // Never send an unguarded or repeated Back: it could leave Setup.
                if (!backPressed) {
                    device.pressBack()
                    backPressed = true
                }
            } else {
                val now = SystemClock.uptimeMillis()
                if (hiddenSince == null) hiddenSince = now
                // Allow pending IME show/hide and layout updates to settle.
                if (now - hiddenSince >= 500L) {
                    device.waitForIdle()
                    return
                }
            }
            SystemClock.sleep(100)
        }
        fail("IME did not stay hidden after text entry")
    }

    private fun findEditable(
        node: AccessibilityNodeInfo,
        tag: String,
        insideField: Boolean = false,
    ): AccessibilityNodeInfo? {
        val inside = insideField || node.viewIdResourceName == tag
        if (inside && node.isEnabled && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            return AccessibilityNodeInfo.obtain(node)
        }
        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            try { findEditable(child, tag, inside)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    private fun reveal(container: String, tag: String, direction: Direction = Direction.DOWN) {
        val deadline = SystemClock.uptimeMillis() + timeout
        val target = By.res(tag).enabled(true)
        repeat(12) {
            device.waitForIdle()
            // Re-query after each layout change; lazy items may exist before usable.
            if (device.findObject(target)?.visibleBounds?.isEmpty == false) return
            if (SystemClock.uptimeMillis() >= deadline) fail("Timed out revealing $tag in $container")
            requireNode(By.res(container), "Scroll container $container").scroll(direction, 0.35f)
            device.wait(Until.hasObject(target), 500L)
        }
        fail("Could not reveal $tag in $container")
    }

    private fun activate(tag: String) {
        requireNode(By.res(tag).enabled(true), "Enabled control $tag")
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: fail("No active window for $tag")
        val focused = try { requestFocus(root, tag) } finally { root.recycle() }
        if (!focused) fail("Could not request remote focus for $tag")
        requireNode(By.res(tag).focused(true), "Remote focus on $tag")
        device.pressDPadCenter()
        device.waitForIdle()
    }

    private fun requestFocus(node: AccessibilityNodeInfo, tag: String): Boolean {
        if (node.viewIdResourceName == tag) return node.isFocused ||
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            try { if (requestFocus(child, tag)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun fail(message: String): Nothing {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: instrumentation.context.getExternalFilesDir(null)
        val directory = File(output, "baseline-failures")
        val stem = "window-${SystemClock.uptimeMillis()}"
        val dump = File(directory, "$stem.xml")
        val screenshot = File(directory, "$stem.png")
        val failure = AssertionError(
            "$message. UI hierarchy: ${dump.absolutePath}; screenshot: ${screenshot.absolutePath}"
        )
        // AGP pulls additionalTestOutputDir before uninstalling the test APK.
        // Preserve the original assertion even if either diagnostic cannot be saved.
        runCatching {
            check(directory.isDirectory || directory.mkdirs()) { "Could not create $directory" }
            device.dumpWindowHierarchy(dump)
        }.onFailure { failure.addSuppressed(it) }
        runCatching {
            check(directory.isDirectory || directory.mkdirs()) { "Could not create $directory" }
            check(device.takeScreenshot(screenshot)) { "Could not capture $screenshot" }
        }.onFailure { failure.addSuppressed(it) }
        throw failure
    }
}
