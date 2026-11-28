package dev.lumenara.tv

import kotlinx.coroutines.CompletableDeferred

/** Main-thread session state. It outlives the STARTED-only engine effects. */
internal class PlaybackOwnership(val itemId: String, initial: Long = -1) {
    internal class Binding internal constructor(val itemId: String, val generation: Long)
    data class Snapshot(val position: Long, val duration: Long)

    private var generation = 0L
    private var active: Binding? = null
    private var mediaId: String? = null
    private var pendingInitial = initial
    private var snapshot: Snapshot? = null
    private var inputRelease: CompletableDeferred<Unit>? = null
    var wantsPlay = true
    val hasBoundMedia: Boolean get() = active != null

    fun newBinding() = Binding(itemId, ++generation)

    fun startPosition(saved: Long): Long = snapshot?.position ?: pendingInitial.takeIf { it >= 0 } ?: saved

    /** Consume the transfer only once media has actually been installed in the engine. */
    fun bind(binding: Binding, mediaId: String, start: Long) {
        check(binding.itemId == itemId)
        active = binding
        this.mediaId = mediaId
        pendingInitial = -1
        snapshot = Snapshot(start.coerceAtLeast(0), 0)
    }

    fun owns(binding: Binding, actualMediaId: String?): Boolean =
        active === binding && binding.itemId == itemId && mediaId == actualMediaId

    fun capture(binding: Binding, actualMediaId: String?, position: Long, duration: Long): Snapshot? {
        if (!owns(binding, actualMediaId) || position < 0) return null
        return Snapshot(position, duration.coerceAtLeast(0)).also { snapshot = it }
    }

    fun unbind(binding: Binding) {
        if (active === binding) { active = null; mediaId = null }
    }

    fun transfer(position: Long) {
        // An outgoing engine's asynchronous cleanup cannot overwrite a handoff.
        active = null
        mediaId = null
        if (position >= 0) {
            snapshot = null
            pendingInitial = position
        }
    }

    fun ownsMedia(actualMediaId: String?): Boolean = active != null && mediaId == actualMediaId

    // VLC stop/fd close is asynchronous. A handoff must wait before Media3 opens its input.
    fun holdInput(): CompletableDeferred<Unit> {
        check(inputRelease == null)
        return CompletableDeferred<Unit>().also { inputRelease = it }
    }

    suspend fun awaitInputRelease() { inputRelease?.await() }

    fun releaseInput(release: CompletableDeferred<Unit>) {
        if (inputRelease === release) inputRelease = null
        release.complete(Unit)
    }
}
