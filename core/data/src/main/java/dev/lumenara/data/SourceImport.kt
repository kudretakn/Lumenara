package dev.lumenara.data

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call

internal fun importCount(value: Int): String = String.format(java.util.Locale.forLanguageTag("tr"), "%,d", value)

class ImportProgress {
    private val current = MutableStateFlow("")
    val stage = current.asStateFlow()
    private val resolved = MutableStateFlow("")
    val address = resolved.asStateFlow()

    fun reset() { current.value = ""; resolved.value = "" }
    internal fun report(stage: String) { current.value = stage }
    internal fun resolved(address: String) { resolved.value = address }
}

/** A cancellation monitor closes sockets even while the import thread is blocked in read(). */
internal class ImportCalls : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ImportCalls>
    private val calls = mutableSetOf<Call>()
    private var cancelled = false

    @Synchronized fun track(call: Call) {
        if (cancelled) call.cancel() else calls += call
    }
    @Synchronized fun release(call: Call) { calls -= call }
    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }
}

internal class SourceImports(private val lock: Mutex) {
    private val waiting = AtomicInteger()
    val userWaiting: Boolean get() = waiting.get() > 0

    suspend fun <T> run(progress: ImportProgress? = null, timeoutMillis: Long = 15 * 60_000L,
        onFailure: suspend () -> Unit = {},
        block: suspend () -> T): T {
        // Wrap the result so a legitimate nullable result is not confused with a timeout.
        val result = withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                val calls = ImportCalls()
                val monitor = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { calls.cancel() }
                }
                try {
                    withContext(Dispatchers.IO + calls) {
                        waiting.incrementAndGet()
                        progress?.report("Sırada bekleniyor…")
                        try { lock.lock() } finally { waiting.decrementAndGet() }
                        try {
                            val value = block()
                            currentCoroutineContext().ensureActive()
                            Result.success(value)
                        } catch (error: Exception) {
                            withContext(NonCancellable) { onFailure() }
                            throw error
                        } finally { lock.unlock() }
                    }
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw error
                } finally { monitor.cancel() }
            }
        } ?: throw SourceFailure("Kaynak işlemi 15 dakika içinde tamamlanamadı. Yeniden deneyin veya başka bir sunucu adresi kullanın.")
        return result.getOrThrow()
    }
}
