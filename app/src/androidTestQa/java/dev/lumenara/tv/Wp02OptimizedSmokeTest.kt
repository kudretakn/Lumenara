package dev.lumenara.tv

import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * QA-only, emulator-only black-box smoke. No app/Room/Hilt/player implementation
 * references are linked from the test APK or added to production keep rules.
 * Accessibility sets text/focus; every activation and player action uses real DPAD keys.
 */
@RunWith(AndroidJUnit4::class)
class Wp02OptimizedSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @get:Rule
    val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            // The runner pulls external files even when instrumentation fails.
            runCatching {
                val directory = java.io.File(context.getExternalFilesDir(null), "wp02-failure")
                check(directory.mkdirs() || directory.isDirectory)
                device.dumpWindowHierarchy(java.io.File(directory, "window.xml"))
                device.takeScreenshot(java.io.File(directory, "screen.png"))
            }.onFailure { android.util.Log.w("Wp02Smoke", "Failure evidence unavailable", it) }
        }
    }

    @Test
    fun optimizedAppImportsEncryptedCatalogSchedulesWorkerAndSwitchesBothEngines() {
        assertTrue("Use the isolated optimized QA APK", context.packageName.endsWith(".qa"))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertTrue("Run only on an x86 emulator, never on the owner's TV",
            Build.SUPPORTED_ABIS.any { it == "x86" || it == "x86_64" } &&
                (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"))

        // WorkManager persists this name and reflectively invokes this exact constructor.
        val parameters = context.classLoader.loadClass("androidx.work.WorkerParameters")
        val worker = context.classLoader.loadClass("dev.lumenara.data.RefreshWorker")
        assertNotNull(worker.getConstructor(android.content.Context::class.java, parameters))

        val suffix = UUID.randomUUID().toString()
        val name = "Wp02 $suffix"
        val first = "Wp02 One $suffix"
        val second = "Wp02 Two $suffix"
        val bytes = instrumentation.context.assets.open("lumenara-test.ts").use { it.readBytes() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath == "/list") return MockResponse().setBody(
                        "#EXTM3U\n#EXTINF:-1 group-title=\"Wp02\",$first\n" +
                            "${server.url("/one.ts?wp02-token=fixture")}\n" +
                            "#EXTINF:-1 group-title=\"Wp02\",$second\n" +
                            "${server.url("/two.ts?wp02-token=fixture")}\n")
                    return MockResponse().setHeader("Content-Type", "video/mp2t")
                        .setBody(Buffer().write(bytes))
                }
            }
            server.start()
            launch()
            await("setup or Hub") { nodeWithTag("setup-screen") != null || nodeWithTag("hub") != null }
            if (nodeWithTag("setup-screen") == null) openSources()
            activate("add M3U") { nodeWithTag("setup-add-m3u") }
            setText("setup-name", name)
            setText("setup-address", server.url("/list?wp02-token=fixture").toString())
            activate("advanced setup") { nodeWithTag("setup-advanced") }
            activate("HTTP compatibility") { nodeWithTag("setup-http-compat") }
            activate("submit source") { nodeWithTag("setup-submit") }
            await("populated Hub") { nodeWithTag("hub") != null }

            // Real Room-generated implementation and Vault write path, through the real UI.
            val sourceId = scalar("SELECT id FROM sources WHERE name = ?", name)!!
            assertEquals("13", scalar("PRAGMA user_version"))
            val endpoint = scalar("SELECT endpoint FROM sources WHERE id = ?", sourceId)!!
            assertFalse(endpoint.contains("wp02-token"))
            assertFalse(endpoint.startsWith("http"))
            val encryptedUrl = scalar("SELECT encryptedUrl FROM items WHERE source = ? LIMIT 1", sourceId)!!
            assertFalse(encryptedUrl.contains("wp02-token"))
            assertFalse(encryptedUrl.startsWith("http"))
            val wrapped = context.getSharedPreferences("vault", 0).getString("data-key", null)
            assertFalse("Vault must retain a wrapped data key", wrapped.isNullOrEmpty())

            // Finish the old Activity before starting a fresh Activity/ViewModel. CLEAR_TASK
            // can start the replacement before the old onStop, which resets RefreshGate's
            // global foreground flag and leaves startup idle work waiting indefinitely.
            finishActivity()
            // Age only our fixture. The real startup path queues a stale-source check.
            // Foreground refresh defers, so a successful
            // startup-refresh plus an enqueued deferred-refresh proves default WorkerFactory
            // + doWork; the five-minute follow-up itself should not run during this wait.
            SQLiteDatabase.openDatabase(context.getDatabasePath("lumenara.db").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.compileStatement("UPDATE sources SET refreshed = 0 WHERE id = ?").use { statement ->
                    statement.bindString(1, sourceId)
                    assertEquals(1, statement.executeUpdateDelete())
                }
            }
            assertEquals("Fixture must be stale by the app's elapsed-time rule", "0",
                scalar("SELECT refreshed FROM sources WHERE id = ?", sourceId))
            // Loopback imports do not prove that Android considers the emulator connected.
            // Keep the real CONNECTED constraint; wait for the boot-time network to validate.
            await("validated network for CONNECTED worker", 90_000,
                onTimeout = { workerDiagnostics(sourceId, "<not scheduled>", null) }) {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            val workDatabase = java.io.File(context.noBackupFilesDir, "androidx.work.workdb")
            val previousDeferredIds = if (workDatabase.exists()) {
                SQLiteDatabase.openDatabase(workDatabase.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                    database.rawQuery("""SELECT work_spec_id FROM WorkName
                        WHERE name = 'deferred-refresh'""", null).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    }
                }
            } else emptyList()
            // APPEND_OR_REPLACE leaves a new follow-up BLOCKED with last_enqueue_time=-1
            // when earlier work exists. A before/after ID comparison proves new work in
            // both ENQUEUED and BLOCKED states without accepting an old follow-up.
            val excludedDeferredIds = previousDeferredIds + ""
            val excludedPlaceholders = excludedDeferredIds.joinToString { "?" }
            val scheduledAfter = System.currentTimeMillis().toString()
            var lastWorkQueryError: Throwable? = null
            val diagnostics = { workerDiagnostics(sourceId, scheduledAfter, lastWorkQueryError) }
            launch()
            await("recreated Hub", onTimeout = diagnostics) { nodeWithTag("hub") != null }
            await("successful worker and deferred follow-up", 90_000, onTimeout = diagnostics) {
                // WorkManager 2.11.1 opens its database in no_backup, not databases/.
                workDatabase.exists() && runCatching { readScalar(workDatabase.path,
                    """SELECT w.id FROM WorkSpec w JOIN WorkName n ON n.work_spec_id = w.id
                        WHERE n.name = 'startup-refresh' AND w.worker_class_name = ?
                        AND w.state = 2 AND w.last_enqueue_time >= CAST(? AS INTEGER)
                        AND EXISTS (SELECT 1 FROM WorkSpec d JOIN WorkName dn ON dn.work_spec_id = d.id
                            WHERE dn.name = 'deferred-refresh' AND d.worker_class_name = ?
                            AND d.state IN (0, 4) AND d.initial_delay > 0
                            AND d.id NOT IN ($excludedPlaceholders)) LIMIT 1""",
                    "dev.lumenara.data.RefreshWorker", scheduledAfter,
                    "dev.lumenara.data.RefreshWorker", *excludedDeferredIds.toTypedArray()) }
                    .onFailure { lastWorkQueryError = it }.getOrNull() != null
            }

            val beforeMedia3 = frameCount("media3")
            // Exported voice-search flow resolves the fixture via Room, then decrypts its URL.
            context.startActivity(Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH")
                .setClassName(context.packageName, "dev.lumenara.tv.MainActivity")
                .putExtra(SearchManager.QUERY, first)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitFrame("media3", beforeMedia3)
            device.pressDPadLeft()
            await("Media3 mini-guide") { nodeWithTag("mini-guide") != null }
            closeGuide("Media3")

            openPlayerOptions()
            activate("switch Media3 to VLC") { optionWithText("Motor: Media3/VLC") }
            await("VLC surface") { nodeWithTag("vlc-player") != null }
            await("VLC options dismissed") { nodeWithTag("player-options") == null }
            settleUi()
            val beforeVlc = frameCount("vlc")
            // A new item arms the actual frame counter after switching the engine.
            device.pressDPadDown()
            awaitFrame("vlc", beforeVlc)
            device.pressDPadLeft()
            await("VLC mini-guide") { nodeWithTag("mini-guide") != null }
            closeGuide("VLC")

            openPlayerOptions()
            activate("switch VLC to Media3") { optionWithText("Motor: VLC/Media3") }
            await("Media3 surface return") {
                nodeWithTag("vlc-player") == null && nodeWithTag("player-options") == null
            }
            settleUi()
            val beforeReturn = frameCount("media3")
            device.pressDPadUp()
            awaitFrame("media3", beforeReturn)
            assertEquals("Vault must reuse the existing wrapped key", wrapped,
                context.getSharedPreferences("vault", 0).getString("data-key", null))
            assertEquals(endpoint, scalar("SELECT endpoint FROM sources WHERE id = ?", sourceId))

            // Best-effort cleanup of this test's source via the UI. The isolated .qa package
            // runs on a disposable CI emulator, so cleanup is not part of the acceptance.
            runCatching {
                openSources()
                activate("delete fixture") { sourceButton(sourceId, "Sil") }
                activate("confirm fixture deletion") { sourceButton(sourceId, "Silmeyi onayla") }
                await("fixture removal") { scalar("SELECT id FROM sources WHERE id = ?", sourceId) == null }
            }.onFailure { android.util.Log.w("Wp02Smoke", "Fixture cleanup skipped", it) }
        }
    }

    private fun finishActivity() {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        lateinit var previous: Activity
        instrumentation.runOnMainSync {
            previous = monitor.getActivitiesInStage(Stage.RESUMED).single {
                it.componentName.className == "dev.lumenara.tv.MainActivity"
            }
            previous.finish()
        }
        await("old Activity destruction") {
            var destroyed = false
            instrumentation.runOnMainSync {
                destroyed = monitor.getLifecycleStageOf(previous) == Stage.DESTROYED
            }
            destroyed
        }
    }

    private fun launch() {
        context.startActivity(Intent(Intent.ACTION_MAIN)
            .setClassName(context.packageName, "dev.lumenara.tv.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openSources() {
        for (attempt in 0 until 4) {
            if (nodeWithTag("hub") != null) break
            device.pressBack()
            device.waitForIdle()
        }
        // The voice-search player has no Hub behind it; Back may leave the app.
        if (nodeWithTag("hub") == null) launch()
        await("Hub return") { nodeWithTag("hub") != null }
        activate("Hub settings") { nodeWithTag("hub-Ayarlar") }
        activate("source settings") { nodeWithTag("settings-group-Kaynaklar") }
        activate("IPTV sources") { nodeWithText("IPTV Kaynakları") }
        await("source setup") { nodeWithTag("setup-screen") != null }
    }

    private fun sourceButton(id: String, text: String): AccessibilityNodeInfo? {
        var ancestor = nodeWithTag("source-edit-$id")?.parent
        while (ancestor != null) {
            find(ancestor) { it.text?.toString() == text }?.let { return it }
            ancestor = ancestor.parent
        }
        return null
    }

    private fun setText(tag: String, value: String) {
        await("field $tag") { nodeWithTag(tag) != null }
        assertTrue(nodeWithTag(tag)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    private fun closeGuide(engine: String) {
        device.pressBack()
        await("$engine mini-guide dismissed") { nodeWithTag("mini-guide") == null }
        settleUi() // Let the player's frame-delayed focus restoration finish before MENU.
    }

    private fun openPlayerOptions() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        await("player options") { nodeWithTag("player-options") != null }
        settleUi() // The panel requests its initial focus after its first frame.
    }

    private fun optionWithText(text: String) = find(nodeWithTag("player-options")) {
        it.isVisibleToUser && it.text?.toString() == text
    }

    private fun focusable(target: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var node = target() ?: return null
        // Text can be accessibility-focusable without owning the button's DPAD action.
        while (!node.isFocusable || !node.isClickable) node = node.parent ?: return null
        return node.takeIf { it.isEnabled && it.isVisibleToUser }
    }

    private fun activate(label: String, target: () -> AccessibilityNodeInfo?) {
        settleUi()
        // API28's waitForIdle can return immediately with an old accessibility tree.
        // Pace real DPAD scrolling so lazy composition can catch up; never select a
        // prefetched/offscreen row or keep a node from before a recomposition.
        var scrolls = 0
        await("visible control: $label", 45_000) {
            if (focusable(target) != null) true else {
                if (scrolls++ < 50) {
                    device.pressDPadDown()
                    settleUi()
                    SystemClock.sleep(250)
                }
                false
            }
        }
        var focusedPolls = 0
        await("stable DPAD focus: $label") {
            val current = focusable(target)
            if (current?.isFocused == true) focusedPolls++ else {
                focusedPolls = 0
                current?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            focusedPolls >= 3
        }
        device.pressDPadCenter()
        settleUi()
    }

    private fun settleUi() {
        instrumentation.waitForIdleSync()
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { drawn.countDown() }
            }
        }
        assertTrue("UI must draw after remote input", drawn.await(10, TimeUnit.SECONDS))
    }

    private fun nodeWithTag(tag: String) = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.isVisibleToUser && it.viewIdResourceName == tag
    }

    private fun nodeWithText(text: String) = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.isVisibleToUser && it.text?.toString() == text
    }

    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) find(root.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun scalar(sql: String, vararg args: String) =
        readScalar(context.getDatabasePath("lumenara.db").path, sql, *args)

    private fun readScalar(path: String, sql: String, vararg args: String): String? =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery(sql, arrayOf(*args)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }

    private fun workerDiagnostics(sourceId: String, scheduledAfter: String, queryError: Throwable?): String =
        buildString {
            appendLine("scheduledAfter=$scheduledAfter now=${System.currentTimeMillis()}")
            appendLine("source.refreshed=" + runCatching {
                scalar("SELECT refreshed FROM sources WHERE id = ?", sourceId) ?: "<source missing>"
            }.getOrElse { "<read failed: $it>" })
            appendLine("last WorkSpec polling error=${queryError ?: "<none>"}")
            appendLine("network=" + runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                "active=${manager.activeNetwork} capabilities=${manager.getNetworkCapabilities(manager.activeNetwork)}"
            }.getOrElse { "<read failed: $it>" })
            val file = java.io.File(context.noBackupFilesDir, "androidx.work.workdb")
            appendLine("WorkManager database=${file.path} exists=${file.exists()}")
            if (file.exists()) append(runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                    // Dump every persisted column, including all embedded Constraints fields
                    // and network/content-trigger blobs. Read the live database with its WAL.
                    database.rawQuery("""SELECT w.*,
                        (SELECT GROUP_CONCAT(n.name, ',') FROM WorkName n WHERE n.work_spec_id = w.id) AS unique_names
                        FROM WorkSpec w WHERE w.worker_class_name = ? OR w.id IN
                        (SELECT work_spec_id FROM WorkName WHERE name IN ('iptv', 'startup-refresh', 'deferred-refresh'))
                        ORDER BY w.last_enqueue_time, w.id""", arrayOf("dev.lumenara.data.RefreshWorker")).use { cursor ->
                        buildString {
                            appendLine("Relevant WorkSpec rows=${cursor.count} (state: 0=ENQUEUED, 1=RUNNING, 2=SUCCEEDED, 3=FAILED, 4=BLOCKED, 5=CANCELLED)")
                            while (cursor.moveToNext()) {
                                appendLine((0 until cursor.columnCount).joinToString(prefix = "{", postfix = "}") { column ->
                                    val value = when (cursor.getType(column)) {
                                        android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                                        android.database.Cursor.FIELD_TYPE_BLOB -> "0x" + cursor.getBlob(column)
                                            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                                        else -> cursor.getString(column)
                                    }
                                    "${cursor.getColumnName(column)}=$value"
                                })
                            }
                        }
                    }
                }
            }.getOrElse { "<WorkSpec dump failed: $it>" })
        }

    private fun frameCount(engine: String) = device.executeShellCommand(
        "logcat -d --pid=${Process.myPid()} -s LumenaraPerf:I '*:S'"
    ).lineSequence().count { it.contains("zap_first_frame ") && it.contains("engine=$engine") }

    private fun awaitFrame(engine: String, before: Int) =
        await("$engine decoded frame", 60_000) { frameCount(engine) > before }

    private fun await(label: String, timeout: Long = 30_000, onTimeout: () -> String = { "" }, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $label (active package=${device.currentPackageName})\n${onTimeout()}")
    }
}
