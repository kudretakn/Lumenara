package dev.lumenara.tv

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.*
import dev.lumenara.catalog.contentKey
import dev.lumenara.data.Mark
import dev.lumenara.data.Member
import dev.lumenara.data.Preferences
import dev.lumenara.data.Repository
import dev.lumenara.data.SourceSpec
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * WP11 emulator-only fault injector. No Activity, HTTP listener, exported component or log payload.
 * Reflection decorates the CURRENT production storage calls, without adding production test hooks
 * or copying RestoreTransaction. A renamed hook fails the test instead of silently losing coverage.
 * The parent instrumentation process never opens these stores (DataStore is single-process).
 */
class RecoveryProbe : Service() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fixture: Wp11RecoveryContext
    private var repository: Repository? = null
    private var database: Any? = null
    private var preferences: Preferences? = null
    @Volatile private var boundary = ""
    @Volatile private var reply: Messenger? = null
    @Volatile private var journalReady = false
    private val parked = CountDownLatch(1)

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            // The process can be killed while its IO worker is paused, never the runner process.
            if (message.what == 2) {
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
            val request = Bundle(message.data)
            val target = message.replyTo ?: return
            work.launch {
                try {
                    execute(request, target)
                } catch (failure: Throwable) {
                    // Never send exception messages, stack traces, URLs, credentials or fixture values.
                    val cause = if (failure is InvocationTargetException) failure.targetException else failure
                    send(target, "error", if (cause is Wp11Invariant) cause.code else cause.javaClass.simpleName,
                        if (cause is Wp11Invariant) cause.reason else "probe-exception")
                }
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        check(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && "x86_64" in Build.SUPPORTED_ABIS) {
            "WP11 requires an x86_64 emulator"
        }
        fixture = Wp11RecoveryContext(this)
    }

    override fun onBind(intent: Intent) = messenger.binder

    override fun onDestroy() {
        parked.countDown()
        work.cancel()
        super.onDestroy()
    }

    private suspend fun execute(request: Bundle, target: Messenger) {
        when (request.getString("command")) {
            "seed" -> {
                check(repository == null) // Seeding must start in a fresh process.
                fixture.reset()
                val repo = open()
                val endpoint = request.getString("endpoint") ?: error("WP11 endpoint missing")
                check(java.net.URI(endpoint).host in setOf("127.0.0.1", "localhost"))
                val spec = SourceSpec(endpoint, true)
                File(fixture.filesDir, "wp11-spec.sha256").writeBytes(specDigest(spec))
                repo.add("Test · WP11", spec)
                // Non-sensitive fixture identity, retained across kills and source re-creation.
                File(fixture.filesDir, "wp11-source.id").writeText(sourceId())
                setState(new = true)
                // Persist only the normal encrypted v1 backup; never a plaintext fixture snapshot.
                File(fixture.filesDir, "wp11-backup").writeBytes(repo.createBackup(password()))
                setState(new = false)
                if (request.getString("operation") == "add") repo.remove(sourceId())
                verifyHealthy()
                send(target, "seeded")
            }
            "mutate" -> {
                val repo = open()
                journalReady = false
                reply = target
                boundary = request.getString("boundary") ?: error("WP11 boundary missing")
                when (request.getString("operation")) {
                    "restore" -> {
                        val preview = repo.previewBackup(File(fixture.filesDir, "wp11-backup").readBytes(), password())
                        val result = repo.restoreBackup(preview)
                        invariant(result.skipped == 0 && result.guideFailures == 0, "restore-counts")
                    }
                    "delete" -> repo.remove(sourceId())
                    "add" -> {
                        val endpoint = request.getString("endpoint") ?: error("WP11 endpoint missing")
                        check(java.net.URI(endpoint).host in setOf("127.0.0.1", "localhost"))
                        repo.add("Test · WP11", SourceSpec(endpoint, true))
                    }
                    else -> error("WP11 operation missing")
                }
                // Every requested boundary must be observed. Completion without a hit is a failure.
                send(target, "error", "BoundaryNotReached")
            }
            "verify" -> {
                val repo = open() // Production constructor replays the journal BEFORE this returns.
                verifyHealthy()
                val operation = request.getString("operation")
                val committed = request.getBoolean("committed")
                val absent = (operation == "delete" && committed) || (operation == "add" && !committed)
                invariant(repo.library.snapshot().isEmpty() == absent, "source-atomicity")
                if (absent) {
                    // A committed deletion must retain every mark/reference in the encrypted orphan
                    // pool. Re-add proves usability, not just the existence of a nonempty blob.
                    invariant(prefs().secret("orphan_history").isNotEmpty(), "orphan-present")
                    val endpoint = request.getString("endpoint") ?: error("WP11 endpoint missing")
                    check(java.net.URI(endpoint).host in setOf("127.0.0.1", "localhost"))
                    repo.add("Test · WP11", SourceSpec(endpoint, true))
                }
                val sameSource = operation == "restore" || (operation == "delete" && !committed)
                invariant((sourceId() == originalSourceId()) == sameSource, "source-identity")
                verifyState(new = operation == "restore" && committed)
                verifyHealthy()
                send(target, "verified")
            }
            "upgrade" -> {
                open()
                verifyHealthy()
                verifyState(new = request.getBoolean("committed"))
                send(target, "verified")
            }
            else -> error("WP11 command missing")
        }
    }

    private fun open(): Repository {
        repository?.let { return it }
        val repo = Repository.get(fixture)
        check(field(repo, "context") === fixture) { "WP11 fixture isolation" }
        repository = repo
        preferences = field(repo, "preferences") as Preferences
        val originalDb = field(repo, "db")
        // Room is an implementation dependency of core/data. Keep reflection here rather than
        // changing shared Gradle ownership or exposing internal storage APIs just for the probe.
        val room = Class.forName("androidx.room.Room")
        val dbType = Class.forName("dev.lumenara.data.LibraryDb")
        val builder = room.getMethod("databaseBuilder", Context::class.java, Class::class.java, String::class.java)
            .invoke(null, fixture, dbType, "lumenara.db")
        val migrationType = Class.forName("androidx.room.migration.Migration")
        val migrations = java.lang.reflect.Array.newInstance(migrationType, 6)
        val companion = dbType.getField("Companion").get(null)
        (7..12).forEachIndexed { index, from ->
            java.lang.reflect.Array.set(migrations, index,
                companion.javaClass.getMethod("getMIGRATION_${from}_${from + 1}").invoke(companion))
        }
        builder.javaClass.getMethod("addMigrations", migrations.javaClass).invoke(builder, migrations)
        val journalType = Class.forName("androidx.room.RoomDatabase\$JournalMode")
        builder.javaClass.getMethod("setJournalMode", journalType)
            .invoke(builder, journalType.getField("WRITE_AHEAD_LOGGING").get(null))
        val callbackType = Class.forName("androidx.room.RoomDatabase\$QueryCallback")
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, args ->
            if (method.name == "onQuery" && args?.get(0)?.toString()?.trim()
                    ?.equals("PRAGMA wal_checkpoint(FULL)", ignoreCase = true) == true) {
                // QueryCallback executes synchronously BEFORE checkpoint, AFTER the actual DAO save.
                hit("after_undo")
            }
            null
        }
        builder.javaClass.getMethod("setQueryCallback", callbackType, Executor::class.java)
            .invoke(builder, callback, Executor { it.run() })
        val replacement = builder.javaClass.getMethod("build").invoke(builder)
        replace(repo, "db", replacement)
        replace(repo, "library", replacement.javaClass.getMethod("library").invoke(replacement))
        replace(repo, "guide", replacement.javaClass.getMethod("guide").invoke(replacement))
        originalDb.javaClass.getMethod("close").invoke(originalDb)
        database = replacement

        val coordinator = field(repo, "restoreTransaction")
        val storage = field(coordinator, "storage")
        val storageType = Class.forName("dev.lumenara.data.RestoreStorage")
        val decorated = Proxy.newProxyInstance(storageType.classLoader, arrayOf(storageType)) { _, method, args ->
            if (method.name == "savePending") hit("before_undo")
            invokeAfter(storage, method, args) {
                when (method.name) {
                    "savePending" -> { journalReady = true; hit("after_checkpoint") }
                    "clearPending" -> if (journalReady) hit("before_commit")
                    "transaction" -> if (journalReady) hit("after_commit")
                }
            }
        }
        replace(coordinator, "storage", decorated)
        val settings = field(prefs(), "store")
        val storeType = Class.forName("androidx.datastore.core.DataStore")
        val decoratedStore = Proxy.newProxyInstance(storeType.classLoader, arrayOf(storeType)) { _, method, args ->
            invokeAfter(settings, method, args) {
                if (method.name == "updateData" && journalReady) hit("after_settings")
            }
        }
        replace(prefs(), "store", decoratedStore)
        return repo
    }

    private fun hit(stage: String) {
        if (boundary != stage) return
        boundary = "" // Exactly one hit; no recovery/follow-up write can satisfy the same request.
        send(checkNotNull(reply), "paused", stage)
        // Bounded even if the parent disappears. The test must observe Binder death, not this timeout.
        check(parked.await(60, TimeUnit.SECONDS)) { "WP11 boundary timeout" }
    }

    private fun prefs() = checkNotNull(preferences)
    private fun repo() = checkNotNull(repository)
    private suspend fun sourceId() = repo().library.snapshot().single().id
    private fun originalSourceId() = File(fixture.filesDir, "wp11-source.id").readText().also {
        invariant(it.isNotBlank(), "original-source-identity")
    }
    private suspend fun item() = repo().library.browse(sourceId(), "LIVE", "", "", "", 2).first().single()

    private suspend fun setState(new: Boolean) {
        val row = item()
        repo().updateMark(row.id) { expectedMark(row.id, new) }
        repo().library.collection(Member(row.id, "WP11 collection"))
        prefs().setShowAdult(new)
        prefs().set("audio_${row.id}", if (new) "en" else "tr")
        prefs().set("subtitle_delay_${row.source}|${row.id}", if (new) "750" else "250")
        prefs().set("limit_${row.source}", if (new) "3" else "2")
        prefs().recordLive(row.id)
        prefs().setSecret("wp11", if (new) "wp11-new-private" else "wp11-old-private")
        // Source credentials exercise the Vault independently of preference secrets.
        val source = repo().library.source(row.source)!!
        repo().library.save(source.copy(name = if (new) "Test · WP11 restored" else "Test · WP11"))
    }

    private suspend fun verifyState(new: Boolean) {
        val source = repo().library.snapshot().single()
        val spec = repo().sourceSpec(source.id)
        invariant(specDigest(spec).contentEquals(File(fixture.filesDir, "wp11-spec.sha256").readBytes()), "source-equality")
        invariant(java.net.URI(spec.address).host in setOf("127.0.0.1", "localhost"), "source-usable")
        invariant(spec.allowHttp, "source-policy")
        invariant(source.name == if (new) "Test · WP11 restored" else "Test · WP11", "source-name")
        val row = item()
        val address = repo().address(row)
        invariant(address == java.net.URI(spec.address).resolve("/stream?fixture=wp11-private").toString(), "address-equality")
        invariant(row.id == contentKey(source.id, address), "identity")
        invariant(!source.endpoint.contains(spec.address) && !row.encryptedUrl.contains(address), "catalog-encrypted")
        invariant(repo().library.mark(row.id) == expectedMark(row.id, new), "mark-equality")
        invariant(repo().library.member(row.id) == Member(row.id, "WP11 collection"), "collection-equality")
        invariant(prefs().showAdult.first() == new, "adult-equality")
        verifyText("audio_${row.id}", if (new) "en" else "tr", "audio-equality")
        verifyText("subtitle_delay_${source.id}|${row.id}", if (new) "750" else "250", "delay-equality")
        // A re-added source has a fresh UUID. History carries item choices, not the old source's
        // connection policy (orphan matching even permits a different account on that server).
        // Check zero loss at the original key AND the new source's configured/effective default.
        val originalSource = originalSourceId()
        verifyText("limit_$originalSource", if (new) "3" else "2", "limit-equality")
        if (source.id != originalSource) {
            invariant(!new, "restore-source-identity")
            verifyText("limit_${source.id}", "", "new-source-limit-default")
            invariant(prefs().connections(source.id).first() == 1, "new-source-connections-default")
        }
        invariant(prefs().recentLive.first() == listOf(row.id), "recent-equality")
        invariant(prefs().secret("wp11") == if (new) "wp11-new-private" else "wp11-old-private", "secret-equality")
        invariant(!prefs().text("secret_wp11").first().contains("wp11-"), "secret-encrypted")
    }

    private fun verifyHealthy() {
        val helper = checkNotNull(database).javaClass.getMethod("getOpenHelper").invoke(database)
        val db = helper.javaClass.getMethod("getWritableDatabase").invoke(helper)
        fun query(sql: String) = db.javaClass.getMethod("query", String::class.java).invoke(db, sql) as Cursor
        query("PRAGMA journal_mode").use { invariant(it.moveToFirst() && it.getString(0) == "wal", "wal") }
        query("PRAGMA foreign_key_check").use { invariant(!it.moveToFirst(), "foreign-keys") }
        query("PRAGMA integrity_check").use { invariant(it.moveToFirst() && it.getString(0) == "ok", "integrity") }
        query("SELECT COUNT(*) FROM restore_pending").use { invariant(it.moveToFirst() && it.getInt(0) == 0, "journal-cleared") }
    }

    private fun expectedMark(id: String, new: Boolean) =
        Mark(id, favorite = true, listed = true, position = if (new) 800 else 400, duration = 1000, updated = if (new) 20 else 10)

    private fun password() = "wp11-fixture-password".toCharArray()
    private fun specDigest(spec: SourceSpec) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(spec.encode().toByteArray(Charsets.UTF_8))
    private suspend fun verifyText(key: String, expected: String, code: String) {
        val actual = prefs().text(key).first()
        invariant(actual == expected, code, if (actual.isEmpty()) "setting-missing" else "setting-mismatch")
    }

    private class Wp11Invariant(val code: String, val reason: String) : IllegalStateException("WP11 invariant")
    private fun invariant(value: Boolean, code: String, reason: String = "fixture-mismatch") {
        if (!value) throw Wp11Invariant(code, reason)
    }

    private fun send(target: Messenger, status: String, code: String = "", reason: String = "") {
        target.send(Message.obtain().apply {
            data = Bundle().apply {
                putString("status", status)
                putString("code", code)
                putString("reason", reason)
                putInt("pid", android.os.Process.myPid())
            }
        })
    }

    private fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)!!

    private fun replace(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    /** Suspend calls may complete inline or via resumeWith; pause only after successful completion. */
    private fun invokeAfter(target: Any, method: Method, arguments: Array<out Any?>?, after: () -> Unit): Any? {
        val args = arguments?.map { it }?.toTypedArray() ?: emptyArray()
        @Suppress("UNCHECKED_CAST")
        val continuation = args.lastOrNull() as? Continuation<Any?>
        if (continuation != null) args[args.lastIndex] = object : Continuation<Any?> {
            override val context = continuation.context
            override fun resumeWith(result: Result<Any?>) {
                val delivered = if (result.isSuccess) runCatching { after(); result.getOrThrow() } else result
                continuation.resumeWith(delivered)
            }
        }
        val result = try {
            method.isAccessible = true
            method.invoke(target, *args)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        if (result !== COROUTINE_SUSPENDED) after()
        return result
    }
}

/** Same database/DataStore leaf names; isolated test files and SharedPreferences namespace only. */
private class Wp11RecoveryContext(base: Context) : ContextWrapper(base) {
    private val root = File(base.filesDir, "wp11-recovery")
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
    override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("wp11_fixture_$name", mode)

    fun reset() {
        check(root.deleteRecursively())
        listOf("vault", "metadata", "watch_next_state").forEach {
            check(getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit())
        }
        // No Keystore deletion: the production alias and production wrapped data key are untouched.
    }
}
