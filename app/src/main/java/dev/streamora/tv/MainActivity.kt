package dev.streamora.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.streamora.design.StreamoraTheme

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val library: LibraryModel by viewModels()

    override fun onSearchRequested(): Boolean {
        library.remoteSearch.value++
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StreamoraTheme { LibraryScreen(library) } }
    }
}
