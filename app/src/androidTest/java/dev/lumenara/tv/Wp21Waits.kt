package dev.lumenara.tv

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.rules.TestRule

/** Bounded observation, with the failing condition and semantics preserved on timeout. */
internal fun ComposeContentTestRule.wp21Await(description: String, condition: () -> Boolean) {
    try {
        waitUntil(30_000, condition)
    } catch (error: ComposeTimeoutException) {
        throw AssertionError("Timed out waiting for $description\n${onRoot().printToString()}", error)
    }
}

internal fun ComposeContentTestRule.wp21AwaitNode(matcher: SemanticsMatcher, present: Boolean = true) =
    wp21Await("$matcher, present=$present") {
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() == present
    }

internal fun ComposeContentTestRule.wp21Focus(matcher: SemanticsMatcher) {
    wp21AwaitNode(matcher)
    onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus)
    wp21AwaitNode(matcher and isFocused())
}

/** A focused Hub navigation card can precede the asynchronous Room shelf emission. */
internal fun ComposeContentTestRule.wp21ScrollTo(containerTag: String, target: SemanticsMatcher) {
    wp21AwaitNode(hasTestTag(containerTag))
    var missing: AssertionError? = null
    try {
        wp21Await("$target inside $containerTag") {
            try {
                onNodeWithTag(containerTag).performScrollToNode(target)
                missing = null
                onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
            } catch (error: AssertionError) {
                // Only an as-yet absent lazy child is retryable. Layout/runner/assertion errors fail.
                if (!error.message.orEmpty().startsWith("No node found that matches")) throw error
                missing = error
                false
            }
        }
    } catch (error: AssertionError) {
        missing?.let { error.addSuppressed(it) }
        throw error
    }
    onNode(target).assertIsDisplayed()
}

/** Let settled Compose content reach the window; elapsed wall time is not a draw signal. */
internal fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.wp21AwaitWindowFrames() {
    waitForIdle()
    val drawn = AtomicBoolean(false)
    runOnUiThread {
        val view = activity.window.decorView
        view.postOnAnimation { view.postOnAnimation { drawn.set(true) } }
    }
    wp21Await("two window frames") { drawn.get() }
    waitForIdle()
}

/** Call after the selected LIVE id is visible. Both engines clear this token on displayed video. */
internal fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.wp21AwaitLiveFrame(itemId: String) {
    wp21AwaitNode(hasContentDescription("Tam ekran oynatıcı"))
    wp21Await("displayed live frame") {
        runOnUiThread { Perf.transitions.zapRequest(itemId) == null }
    }
    wp21AwaitNode(hasTestTag("live-reconnecting"), present = false)
    wp21AwaitNode(hasContentDescription("Tam ekran oynatıcı") and isFocused())
}

/** Player/decoder reads must run on its application looper, never the instrumentation thread. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.wp21AwaitMedia3Ready(address: String) {
    wp21Await("Media3 ready and rendered for selected item") {
        runOnUiThread {
            val player = wp21FindPlayerView(activity.window.decorView)?.player as? ExoPlayer
            if (player == null || player.currentMediaItem?.localConfiguration?.uri?.toString() != address) false else {
                player.playerError?.let { throw AssertionError("Selected Media3 item failed", it) }
                val counters = player.videoDecoderCounters
                counters?.ensureUpdated()
                player.playbackState == Player.STATE_READY && player.isPlaying &&
                    (counters?.renderedOutputBufferCount ?: 0) > 0
            }
        }
    }
    wp21AwaitNode(hasContentDescription("Tam ekran oynatıcı") and isFocused())
}

internal fun wp21FindPlayerView(view: View): PlayerView? {
    if (view is PlayerView) return view
    if (view is ViewGroup) for (index in 0 until view.childCount) {
        wp21FindPlayerView(view.getChildAt(index))?.let { return it }
    }
    return null
}
