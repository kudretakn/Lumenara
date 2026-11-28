package dev.lumenara.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*

/** Generic Binder protocol: no reference to a debug class, so QA/release test APKs still compile. */
internal class Wp11RecoveryClient : Closeable {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val connected = CountDownLatch(1)
    private val died = CountDownLatch(1)
    private val replies = LinkedBlockingQueue<Bundle>()
    private lateinit var binder: IBinder
    private val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) { replies.offer(Bundle(message.data)) }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            try { service.linkToDeath({ died.countDown() }, 0) }
            catch (_: RemoteException) { died.countDown() }
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
        override fun onNullBinding(name: ComponentName) { connected.countDown() }
    }
    private var bound = false

    init {
        // Fail closed before binding; never perform interruption injection on the owner's TV.
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && "x86_64" in Build.SUPPORTED_ABIS) {
            "WP11 requires an x86_64 emulator"
        }
        val intent = Intent().setComponent(ComponentName(context.packageName, "dev.lumenara.tv.RecoveryProbe"))
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        check(bound) { "WP11 debug probe unavailable" }
        try {
            check(connected.await(10, TimeUnit.SECONDS) && ::binder.isInitialized) { "WP11 bind timeout" }
        } catch (failure: Throwable) {
            unbind()
            throw failure
        }
    }

    fun command(command: String, operation: String = "restore", endpoint: String = "",
        boundary: String = "", committed: Boolean = false, expected: String): Bundle {
        assertTrue("WP11 worker already dead", binder.isBinderAlive)
        Messenger(binder).send(Message.obtain().apply {
            what = 1
            replyTo = receiver
            data = Bundle().apply {
                putString("command", command)
                putString("operation", operation)
                putString("endpoint", endpoint)
                putString("boundary", boundary)
                putBoolean("committed", committed)
            }
        })
        val response = replies.poll(35, TimeUnit.SECONDS)
        assertNotNull("WP11 reply timeout for $command/$operation/$boundary", response)
        val result = response!!
        // Replies contain only whitelisted status/stage/class codes; fixture values never cross IPC.
        assertEquals("WP11 probe code=${result.getString("code")}", expected, result.getString("status"))
        assertTrue("WP11 must use a separate process", result.getInt("pid") != android.os.Process.myPid())
        if (expected == "paused") assertEquals(boundary, result.getString("code"))
        return result
    }

    fun killAndAwaitDeath() {
        val killedBinder = binder
        Messenger(killedBinder).send(Message.obtain().apply { what = 2 })
        assertTrue("WP11 child did not die", died.await(5, TimeUnit.SECONDS))
        assertFalse("WP11 Binder survived kill", killedBinder.isBinderAlive)
        // Unbind before another client starts: BIND_AUTO_CREATE must not relaunch a killed worker.
        unbind()
    }

    private fun unbind() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
    }

    override fun close() {
        try { if (bound && ::binder.isInitialized && binder.isBinderAlive) killAndAwaitDeath() }
        finally { unbind() }
    }
}

internal fun wp11Server() = MockWebServer().apply {
    dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path?.startsWith("/list") == true -> MockResponse().setBody(
                "#EXTM3U\n#EXTINF:-1 tvg-id=\"wp11\" group-title=\"WP11\",WP11 channel\n" +
                    url("/stream?fixture=wp11-private"))
            else -> MockResponse().setResponseCode(404)
        }
    }
    start()
}
