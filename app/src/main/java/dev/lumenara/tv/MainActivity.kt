package dev.lumenara.tv

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.lumenara.design.LumenaraTheme

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val library: LibraryModel by viewModels()

    override fun onSearchRequested(): Boolean {
        library.remoteSearch.value++
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handlePlaybackIntent(intent)
        setContent { LumenaraTheme { LibraryScreen(library) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlaybackIntent(intent)
    }

    private fun handlePlaybackIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        parseWatchNextLink(intent.dataString.orEmpty())?.let {
            library.watchNextPlayback.value = it
        }
    }
}
