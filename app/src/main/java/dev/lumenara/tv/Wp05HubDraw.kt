package dev.lumenara.tv

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** One entry per composed image surface, including unresolved addresses. Never stores an ID/URL. */
internal class Wp05HubDraw {
    class Image {
        var bounds by mutableStateOf(Rect.Zero)
        var terminalDrawn by mutableStateOf(false)
    }
    private val images = mutableStateListOf<Image>()
    private val reported = mutableSetOf<String>()
    var viewport by mutableStateOf(Rect.Zero)
    fun add(image: Image) { images.add(image) }
    fun remove(image: Image) { images.remove(image) }
    fun onDraw(rowsReady: Boolean, countsReady: Boolean, focusReady: Boolean, emit: (String) -> Unit) {
        fun report(stage: String, ready: Boolean) { if (ready && reported.add(stage)) emit(stage) }
        val local = rowsReady && countsReady && focusReady
        val artwork = rowsReady && artworkDrawn()
        report("startup_hub_counts_draw", countsReady)
        report("startup_hub_local_draw", local)
        report("startup_hub_artwork_draw", artwork)
        report("startup_hub_full_draw", local && artwork)
    }
    fun artworkDrawn(): Boolean = viewport.width > 0 && viewport.height > 0 && images.all {
        val visible = it.bounds.width > 0 && it.bounds.height > 0 && it.bounds.overlaps(viewport)
        !visible || it.terminalDrawn
    }
}

internal val LocalWp05HubDraw = staticCompositionLocalOf<Wp05HubDraw?> { null }

/** A loading fallback is never terminal. Completion is recorded after its actual content draw. */
@Composable
internal fun Modifier.wp05HubImageDraw(identity: Any?, terminal: Boolean): Modifier {
    val hub = LocalWp05HubDraw.current ?: return this
    val image = remember(hub) { Wp05HubDraw.Image() }
    val previousIdentity = remember(hub) { arrayOf(identity) }
    DisposableEffect(hub, image) {
        hub.add(image)
        onDispose { hub.remove(image) }
    }
    // Clear a previous result before the next draw when an existing request becomes pending.
    SideEffect {
        if (!terminal || previousIdentity[0] != identity) image.terminalDrawn = false
        previousIdentity[0] = identity
    }
    return onGloballyPositioned { image.bounds = it.boundsInWindow() }.drawWithContent {
        drawContent()
        image.terminalDrawn = terminal
    }
}

/** Null means address resolution is pending; a null address in a result means a real local fallback. */
internal data class Wp05HubImage(val address: String?, val role: String = "poster")

internal suspend fun wp05ResolveHubImage(
    backdrop: suspend () -> String?,
    poster: suspend () -> String?,
): Wp05HubImage = backdrop()?.takeIf(String::isNotBlank)?.let { Wp05HubImage(it, "backdrop") }
    ?: Wp05HubImage(poster()?.takeIf(String::isNotBlank))

/** Key the state holder as well as the producer: produceState keys alone retain the old value. */
@Composable
internal fun wp05ResolvedHubImage(vararg identity: Any?, resolve: suspend () -> Wp05HubImage): Wp05HubImage? =
    key(identity.toList()) { produceState<Wp05HubImage?>(null) { value = resolve() }.value }

@Composable
internal fun Wp05PosterImage(item: dev.lumenara.data.Item, resolve: suspend () -> String?,
    content: @Composable (String?) -> Unit) {
    key(item.id, item.logo) {
        val address by produceState<String?>(null) { value = resolve() }
        content(address)
    }
}
