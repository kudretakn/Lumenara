package dev.lumenara.tv

import android.os.Bundle
import android.app.SearchManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
        super.onCreate(savedInstanceState)
        // Start the model's launch snapshot before the first composition.
        val model = library
        if (savedInstanceState == null) {
            handleSearchIntent(intent)
            handlePlaybackIntent(intent)
        }
        setContent { LumenaraTheme { LibraryScreen(model) } }
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
