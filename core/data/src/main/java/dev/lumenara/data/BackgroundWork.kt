package dev.lumenara.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Input can defer startup work at most 20s; ongoing batches have a separate quiet gate. */
class StartupIdleGate(scope: CoroutineScope) {
    private data class Signals(val drawn: Boolean = false, val foreground: Boolean = false,
        val playing: Boolean = false, val deadlineReached: Boolean = false, val needsQuiet: Boolean = false, val revision: Long = 0)
    private val signals = MutableStateFlow(Signals())
    private val ready = MutableStateFlow(false)
    val idle = ready.asStateFlow()
    private val maintenanceReady = MutableStateFlow(false)
    internal val maintenanceIdle = maintenanceReady.asStateFlow()
    val idleOrBackground: Flow<Boolean> = combine(idle, signals) { quiet, state ->
        quiet || !state.foreground
    }.distinctUntilChanged()
    internal val maintenanceIdleOrBackground: Flow<Boolean> = combine(maintenanceIdle, signals) { quiet, state ->
        quiet || !state.foreground
    }.distinctUntilChanged()

    init {
        scope.launch {
            signals.first { it.drawn }
            // Independent of input/lifecycle revisions: a held remote key cannot starve work.
            delay(20_000)
            update { it.copy(deadlineReached = true) }
        }
        scope.launch {
            signals.collectLatest { observed ->
                if (observed.drawn && observed.foreground && !observed.playing) {
                    if (!observed.deadlineReached) delay(15_000)
                    else if (observed.needsQuiet) delay(500)
                    synchronized(this@StartupIdleGate) {
                        if (signals.value == observed) {
                            ready.value = true
                            maintenanceReady.value = true
                        }
                    }
                }
            }
        }
    }

    @Synchronized private fun update(force: Boolean = false, change: (Signals) -> Signals) {
        val next = change(signals.value)
        if (!force && next == signals.value) return
        // Playback/background protection still applies after the startup deadline.
        ready.value = next.drawn && next.foreground && !next.playing && next.deadlineReached
        // Do not revoke the startup deadline on later input. Only ongoing maintenance
        // and speculative work must wait for the short quiet interval again.
        maintenanceReady.value = ready.value && !next.needsQuiet
        // StateFlow may conflate a rapid stop/start or playback open/close. The revision
        // keeps that transition visible even when the final lifecycle flags are identical.
        signals.value = next.copy(revision = signals.value.revision + 1)
    }
    fun onDraw() = update { it.copy(drawn = true) }
    fun onInput() = update(force = true) { it.copy(needsQuiet = it.deadlineReached) }
    fun foreground(value: Boolean) = update { it.copy(foreground = value) }
    fun playing(value: Boolean) = update { it.copy(playing = value) }

    // A worker-only process has no content draw. Publishing must also survive Home/Back.
    suspend fun awaitIdleOrBackground() {
        idleOrBackground.first { it }
    }
}

/** Catalog mutations remain serial. Network/decode/provider waits have a separate lane.
 * Do not nest run/whenIdle or call a repository mutation from inside them. */
class BackgroundWorkQueue(val startup: StartupIdleGate) {
    internal val mutex = Mutex()
    private val speculativeMutex = Mutex()
    private class IdleClosed : CancellationException()

    /** Cancel optional work on input/playback, without ever owning the catalog mutex. */
    suspend fun <T> speculative(allowBackground: Boolean = false, block: suspend () -> T): T {
        val permission = if (allowBackground) startup.maintenanceIdleOrBackground else startup.maintenanceIdle
        while (true) {
            permission.first { it }
            try {
                speculativeMutex.lock()
                try {
                    if (!permission.first()) continue
                    return coroutineScope {
                        val work = async { block() }
                        val monitor = launch {
                            permission.first { !it }
                            work.cancel(IdleClosed())
                        }
                        try { work.await() } finally { monitor.cancel() }
                    }
                } finally { speculativeMutex.unlock() }
            } catch (closed: IdleClosed) {
                currentCoroutineContext().ensureActive()
            }
        }
    }

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> whenIdle(block: suspend () -> T): T = withIdle(startup.idle, block)

    internal suspend fun <T> whenMaintenanceIdle(block: suspend () -> T): T =
        withIdle(startup.maintenanceIdle, block)

    private suspend fun <T> withIdle(permission: StateFlow<Boolean>, block: suspend () -> T): T {
        while (true) {
            permission.first { it }
            mutex.lock()
            try {
                // Input/playback may have arrived while another job owned the lane.
                if (permission.value) return block()
            } finally { mutex.unlock() }
        }
    }
}

val backgroundWork = BackgroundWorkQueue(
    StartupIdleGate(CoroutineScope(SupervisorJob() + Dispatchers.Default)))
