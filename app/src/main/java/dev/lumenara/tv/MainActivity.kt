package dev.lumenara.tv

import android.os.Bundle
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
        // Start the model's launch snapshot before the first composition.
        val model = library
        setContent { LumenaraTheme { LibraryScreen(model) } }
    }
}
