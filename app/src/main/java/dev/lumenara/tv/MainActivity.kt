package dev.lumenara.tv

import android.os.Bundle
import android.app.SearchManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.lumenara.catalog.searchRequest
import dev.lumenara.design.LumenaraTheme

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val library: LibraryModel by viewModels()
    private var stopFrameObservation: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        stopFrameObservation = Perf.observeFrames(window)
        dev.lumenara.data.RefreshGate.foreground = true
    }

    override fun onStop() {
        stopFrameObservation?.invoke()
        stopFrameObservation = null
        dev.lumenara.data.RefreshGate.foreground = false
        super.onStop()
    }

    override fun onSearchRequested(): Boolean {
        library.searchIntent.value = null
        library.watchNextPlayback.value = null
        library.remoteSearch.value++
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Perf.mark("startup_activity")
        super.onCreate(savedInstanceState)
        // Start the model's launch snapshot before the first composition.
        val model = library
        Perf.since("startup_activity", "startup_activity_model_ready")
        if (savedInstanceState == null) {
            handleSearchIntent(intent)
            handlePlaybackIntent(intent)
        }
        Perf.mark("startup_screen")
        setContent {
            val reported = remember {
                Perf.mark("startup_initial_composition")
                booleanArrayOf(false)
            }
            SideEffect { Perf.since("startup_initial_composition", "startup_initial_composition_ready") }
            Box(Modifier.semantics { testTagsAsResourceId = true }.drawWithContent {
                drawContent()
                if (!reported[0]) {
                    reported[0] = true
                    Perf.since("startup_screen", "startup_screen_first_draw")
                    Perf.since("startup_first_frame", "startup_first_frame")
                    model.onScreenFirstDraw()
                }
            }) {
                LumenaraTheme { LibraryScreen(model) {
                    (application as LumenaraApp).prepareHubArtwork()
                    (application as LumenaraApp).startWatchNext()
                } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSearchIntent(intent)
        handlePlaybackIntent(intent)
    }

    private fun handleSearchIntent(intent: Intent?) {
        val request = searchRequest(intent?.action, intent?.getStringExtra(SearchManager.QUERY))
            ?: return
        library.receiveSearchIntent(request)
    }

    private fun handlePlaybackIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        parseWatchNextLink(intent.dataString.orEmpty())?.let {
            library.searchIntent.value = null
            library.remoteSearch.value = 0
            library.watchNextPlayback.value = it
        }
    }
}
