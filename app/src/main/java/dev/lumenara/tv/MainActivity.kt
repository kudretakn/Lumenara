package dev.lumenara.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.lumenara.design.LumenaraTheme

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val library: LibraryModel by viewModels()

    override fun onStart() {
        super.onStart()
        dev.lumenara.data.RefreshGate.foreground = true
    }

    override fun onStop() {
        dev.lumenara.data.RefreshGate.foreground = false
        super.onStop()
    }

    override fun onSearchRequested(): Boolean {
        library.remoteSearch.value++
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumenaraTheme { LibraryScreen(library) } }
    }
}
