package dev.lumenara.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-owned startup. Cancellation of a screen/worker must not interrupt recovery. */
class StartupCoordinator(load: suspend () -> Repository) {
    private val readiness = Wp07StartupReadiness(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), load)
    val state = readiness.state
    val readyOrNull: Repository? get() = readiness.readyOrNull
    suspend fun awaitReady(): Repository = readiness.awaitReady()
}

sealed interface Wp07StartupState {
    data object Loading : Wp07StartupState
    data object Ready : Wp07StartupState
    data class Error(val cause: Throwable) : Wp07StartupState
}

/** Kept generic so the publication/cancellation boundary can be tested without Android. */
internal class Wp07StartupReadiness<T : Any>(scope: CoroutineScope, load: suspend () -> T) {
    private val status = MutableStateFlow<Wp07StartupState>(Wp07StartupState.Loading)
    val state = status.asStateFlow()
    @Volatile private var ready: T? = null
    val readyOrNull: T? get() = ready
    // The result has no parent: a failed recovery belongs to readiness, not the owning scope.
    private val result = CompletableDeferred<T>()
    private val opening by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                val value = load()
                currentCoroutineContext().ensureActive()
                ready = value
                status.value = Wp07StartupState.Ready
                result.complete(value)
            } catch (error: Throwable) {
                status.value = Wp07StartupState.Error(error)
                result.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }.also { job ->
            // A cancelled owner can prevent the launch body from ever running.
            job.invokeOnCompletion { error ->
                if (error != null && !result.isCompleted) {
                    status.value = Wp07StartupState.Error(error)
                    result.completeExceptionally(error)
                }
            }
        }
    }
    suspend fun awaitReady(): T {
        opening.start()
        return result.await()
    }
}

/** Tokens avoid retaining activities and make duplicate lifecycle callbacks harmless. */
class Wp07StartedActivities(private val changed: (Boolean) -> Unit) {
    private val started = mutableSetOf<Any>()
    @Synchronized fun start(token: Any) {
        val wasEmpty = started.isEmpty()
        if (started.add(token) && wasEmpty) changed(true)
    }
    @Synchronized fun stop(token: Any) {
        if (started.remove(token) && started.isEmpty()) changed(false)
    }
}

object Wp07ActivityForeground {
    private val activities = Wp07StartedActivities { RefreshGate.foreground = it }
    fun start(token: Any) = activities.start(token)
    fun stop(token: Any) = activities.stop(token)
}

/** Android's running-memory levels precede UI_HIDDEN numerically. */
fun wp07ShouldTrim(level: Int): Boolean = level == 10 || level == 15 || level >= 20

/** Injection here is deliberate: a trim callback has no path that can create a repository. */
fun <T> wp07TrimMemory(level: Int, peek: () -> T?, trim: (T) -> Unit, clearArtwork: () -> Unit) {
    if (wp07ShouldTrim(level)) {
        peek()?.let(trim)
        clearArtwork()
    }
}
