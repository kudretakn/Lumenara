package dev.lumenara.tv

import dev.lumenara.player.api.EngineKind
import dev.lumenara.player.api.PlaybackSession
import kotlinx.coroutines.CompletableDeferred

/** Item view of the route's one session. Kept outside STARTED-only engine effects. */
internal class PlaybackOwnership(
    val itemId: String,
    initial: Long = -1,
    private val session: PlaybackSession = PlaybackSession(),
    progress: ((Snapshot) -> Unit)? = null,
) {
    internal class Binding internal constructor(internal val token: PlaybackSession.Binding) {
        val itemId: String get() = token.itemId
        val generation: Long get() = token.generation
    }
    data class Snapshot(val position: Long, val duration: Long)
    private var progressWriter = progress
    internal val item = session.item(itemId, initial) { progressWriter?.invoke(Snapshot(it.position, it.duration)) }
    fun configureProgress(write: (Snapshot) -> Unit) {
        if (progressWriter == null) progressWriter = write
    }
    private var active: Binding? = null
    private var inputId: String? = null
    var wantsPlay: Boolean
        get() = item.wantsPlay
        set(value) { item.wantsPlay = value }
    val hasBoundMedia: Boolean get() = session.hasBoundMedia(item)

    fun select(live: Boolean, preferred: EngineKind) = session.select(item, live, preferred)
    fun newBinding() = Binding(session.newBinding(item))
    fun startPosition(saved: Long): Long = session.startPosition(item, saved)
    fun bind(binding: Binding, mediaId: String, start: Long, engine: EngineKind = EngineKind.MEDIA3) {
        check(binding.itemId == itemId)
        session.bind(binding.token, mediaId, start, engine)
        active = binding
        inputId = mediaId
    }
    fun owns(binding: Binding, actualMediaId: String?): Boolean = session.owns(binding.token, actualMediaId)
    fun capture(binding: Binding, actualMediaId: String?, position: Long, duration: Long): Snapshot? =
        session.capture(binding.token, actualMediaId, position, duration)?.let { Snapshot(it.position, it.duration) }

    /** All periodic, completion and cleanup progress goes to the item sink configured once. */
    fun save(binding: Binding, mediaId: String?, position: Long, duration: Long) =
        session.save(binding.token, mediaId, position, duration)
    fun saveCurrent(position: Long, duration: Long) {
        active?.let { save(it, inputId, position, duration) }
    }
    fun captureCurrent(position: Long, duration: Long): Snapshot? =
        active?.let { capture(it, inputId, position, duration) }
    fun unbind(binding: Binding) {
        session.unbind(binding.token)
        if (active === binding) { active = null; inputId = null }
    }
    fun transfer(position: Long) { session.transfer(item, position) }
    fun fallbackToVlc(position: Long): Boolean = session.fallbackToVlc(item, position)
    fun switchTo(engine: EngineKind, position: Long) = session.switchTo(item, engine, position)
    fun ownsMedia(actualMediaId: String?): Boolean = session.ownsMedia(item, actualMediaId)
    fun holdInput(): CompletableDeferred<Unit> = session.holdInput()
    suspend fun awaitInputRelease() = session.awaitInputRelease()
    fun releaseInput(release: CompletableDeferred<Unit>) = session.releaseInput(release)
}
