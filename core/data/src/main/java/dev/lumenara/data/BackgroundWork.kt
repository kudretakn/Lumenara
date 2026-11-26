package dev.lumenara.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** For optional work only, never visible UI. Input can defer work at most 20s after draw. */
class StartupIdleGate(scope: CoroutineScope) {
    private data class Signals(val drawn: Boolean = false, val foreground: Boolean = false,
        val playing: Boolean = false, val deadlineReached: Boolean = false, val revision: Long = 0)
    private val signals = MutableStateFlow(Signals())
    private val ready = MutableStateFlow(false)
    val idle = ready.asStateFlow()

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
                    synchronized(this@StartupIdleGate) {
                        if (signals.value == observed) ready.value = true
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
        // StateFlow may conflate a rapid stop/start or playback open/close. The revision
        // keeps that transition visible even when the final lifecycle flags are identical.
        signals.value = next.copy(revision = signals.value.revision + 1)
    }
    fun onDraw() = update { it.copy(drawn = true) }
    fun onInput() = update(force = true) { it }
    fun foreground(value: Boolean) = update { it.copy(foreground = value) }
    fun playing(value: Boolean) = update { it.copy(playing = value) }

    // A worker-only process has no content draw. Publishing must also survive Home/Back.
    suspend fun awaitIdleOrBackground() {
        combine(idle, signals) { quiet, state -> quiet || !state.foreground }.first { it }
    }
}

/** A single lane for imports, upgrade batches and speculative work on small TV CPUs.
 * Do not nest run/whenIdle or call a repository mutation from inside them. */
class BackgroundWorkQueue(val startup: StartupIdleGate) {
    internal val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> whenIdle(block: suspend () -> T): T {
        while (true) {
            startup.idle.first { it }
            mutex.lock()
            try {
                // Input/playback may have arrived while another job owned the lane.
                if (startup.idle.value) return block()
            } finally { mutex.unlock() }
        }
    }
}

val backgroundWork = BackgroundWorkQueue(
    StartupIdleGate(CoroutineScope(SupervisorJob() + Dispatchers.Default)))
