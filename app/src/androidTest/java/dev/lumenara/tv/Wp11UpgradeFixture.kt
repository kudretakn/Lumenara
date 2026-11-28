package dev.lumenara.tv

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Mark
import dev.lumenara.data.Member
import dev.lumenara.data.Preferences
import dev.lumenara.data.Repository
import dev.lumenara.data.SourceSpec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Runs ONLY as a standalone instrumentation invocation against the previous debug APK. It uses
 * APIs present before WP11, so that APK needs no probe. The next install -r verifies via the new
 * probe in a fresh process. Never call this path from the normal multi-class suite: the process's
 * preferencesDataStore delegate must not have been initialized with another Context.
 */
internal object Wp11UpgradeFixture {
    fun seed(endpoint: String) = runBlocking(Dispatchers.IO) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && "x86_64" in Build.SUPPORTED_ABIS)
        check(java.net.URI(endpoint).host == "127.0.0.1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        // Must agree with the debug probe's storage layout; production paths remain untouched.
        val root = File(target.filesDir, "wp11-recovery")
        check(root.deleteRecursively())
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile?.mkdirs() }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("wp11_fixture_$name", mode)
        }
        listOf("vault", "metadata", "watch_next_state").forEach {
            check(context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
        }
        val repo = Repository.get(context)
        // Do not seed into a singleton initialized by a different test/application context.
        check(repo.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(repo) === context)
        val spec = SourceSpec(endpoint, true)
        File(context.filesDir, "wp11-spec.sha256").writeBytes(java.security.MessageDigest.getInstance("SHA-256")
            .digest(spec.encode().toByteArray(Charsets.UTF_8)))
        repo.add("Test · WP11", spec)
        val source = repo.library.snapshot().single()
        File(context.filesDir, "wp11-source.id").writeText(source.id)
        val row = repo.library.browse(source.id, "LIVE", "", "", "", 2).first().single()
        repo.updateMark(row.id) { Mark(row.id, true, true, 400, 1000, 10) }
        repo.library.collection(Member(row.id, "WP11 collection"))
        val prefs = Preferences(context)
        prefs.setShowAdult(false)
        prefs.set("audio_${row.id}", "tr")
        prefs.set("subtitle_delay_${source.id}|${row.id}", "250")
        prefs.set("limit_${source.id}", "2")
        prefs.recordLive(row.id)
        prefs.setSecret("wp11", "wp11-old-private")
        // Return Unit so a fixture object can never become a runner result string.
        Unit
    }
}
