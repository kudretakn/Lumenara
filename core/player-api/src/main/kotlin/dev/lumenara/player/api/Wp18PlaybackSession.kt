package dev.lumenara.player.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One application-thread owner across items, engines and Home/return. Item handles retain only
 * resume intent; binding, generation and the input-release barrier have a single writer here.
 * Handles are not cached, so an arbitrarily long live session cannot retain the catalog.
 */
class PlaybackSession {
    class Item internal constructor(val id: String, internal var initial: Long,
        internal val progress: (Snapshot) -> Unit) {
        internal var snapshot: Snapshot? = null
        var wantsPlay: Boolean = true
        var retryCount: Int = 0
        var retryGeneration: Int = 0
    }
    class Binding internal constructor(internal val item: Item, val generation: Long) {
        val itemId: String get() = item.id
    }
    data class Snapshot(val position: Long, val duration: Long)

    private var selectedItem: Item? = null
    private var selectedLive = false
    private var switched = false
    private val requested = MutableStateFlow(EngineKind.MEDIA3)
    val requestedEngine = requested.asStateFlow()

    /** Keep a live fallback across zaps, reset the loop guard for each newly selected item. */
    fun select(item: Item, live: Boolean, preferred: EngineKind) {
        if (selectedItem === item) return
        if (!live || !selectedLive) requested.value = preferred
        selectedItem = item
        selectedLive = live
        switched = false
    }

    fun fallbackToVlc(item: Item, position: Long): Boolean {
        if (selectedItem !== item || switched) return false
        switchTo(item, EngineKind.VLC, position)
        return true
    }

    fun switchTo(item: Item, engine: EngineKind, position: Long) {
        if (selectedItem !== item) return
        switched = true
        transfer(item, position)
        requested.value = engine
    }

    private var generation = 0L
    private var active: Binding? = null
    private var mediaId: String? = null
    private var inputRelease: CompletableDeferred<Unit>? = null
    var engine: EngineKind? = null
        private set

    fun item(id: String, initial: Long = -1, progress: (Snapshot) -> Unit = {}): Item = Item(id, initial, progress)
    fun newBinding(item: Item): Binding = Binding(item, ++generation)
    fun hasBoundMedia(item: Item): Boolean = active?.item === item
    fun startPosition(item: Item, saved: Long): Long =
        item.snapshot?.position ?: item.initial.takeIf { it >= 0 } ?: saved

    fun bind(binding: Binding, mediaId: String, start: Long, engine: EngineKind) {
        check(selectedItem == null || selectedItem === binding.item) { "Unselected playback item" }
        check(binding.generation == generation) { "Superseded playback preparation" }
        active = binding
        this.mediaId = mediaId
        this.engine = engine
        binding.item.initial = -1
        binding.item.snapshot = Snapshot(start.coerceAtLeast(0), 0)
    }

    fun owns(binding: Binding, actualMediaId: String?): Boolean =
        active === binding && mediaId == actualMediaId

    /** Every progress writer, including end/disposal, must pass through this gate. */
    fun capture(binding: Binding, actualMediaId: String?, position: Long, duration: Long): Snapshot? {
        if (!owns(binding, actualMediaId) || position < 0) return null
        return Snapshot(position, duration.coerceAtLeast(0)).also { binding.item.snapshot = it }
    }

    fun save(binding: Binding, actualMediaId: String?, position: Long, duration: Long) {
        capture(binding, actualMediaId, position, duration)?.let(binding.item.progress)
    }

    fun unbind(binding: Binding) {
        if (active === binding) { active = null; mediaId = null; engine = null }
    }

    fun transfer(item: Item, position: Long) {
        if (active?.item === item) { active = null; mediaId = null; engine = null }
        if (position >= 0) { item.snapshot = null; item.initial = position }
    }

    fun ownsMedia(item: Item, actualMediaId: String?): Boolean =
        active?.item === item && mediaId == actualMediaId

    fun holdInput(): CompletableDeferred<Unit> {
        check(inputRelease == null) { "Previous input has not closed" }
        return CompletableDeferred<Unit>().also { inputRelease = it }
    }

    suspend fun awaitInputRelease() { inputRelease?.await() }

    fun releaseInput(release: CompletableDeferred<Unit>) {
        if (inputRelease === release) inputRelease = null
        release.complete(Unit)
    }
}
