package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.lumenara.data.*
import dev.lumenara.design.LumenaraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class HubFirstDrawTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun cachedShelvesReleaseTheGateOnFirstDrawAndRestoreShelfFocus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val source = "hub-first-draw-${System.nanoTime()}"
        val channel = Item("$source-live", source, "Cached News", "News", "LIVE", "", "", "cached news", 1, "")
        val movie = Item("$source-movie", source, "Cached Film", "Film", "MOVIE", "", "", "cached film", 1, "")
        val savedRecent = runBlocking { prefs.text("recent_live").first() }
        val gateAtFirstDraw = AtomicReference<Boolean>()
        var model: LibraryModel? = null
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), 2))
                repo.library.save(listOf(channel, movie))
                repo.library.save(Mark(movie.id, position = 10, duration = 100, updated = System.currentTimeMillis()))
                prepareTestCatalog(repo, source)
                prefs.recordLive(channel.id)
            }
            screen.runOnUiThread {
                screen.activity.setContent {}
                model = LibraryModel(dagger.Lazy { repo }, prefs).also { it.selectHubSource(source) }
            }
            screen.waitUntil(10_000) {
                model?.hubRecent?.value?.let { it.source == source && it.rows.isNotEmpty() } == true &&
                    model?.hubResume?.value?.let { it.source == source && it.rows.isNotEmpty() } == true
            }
            screen.runOnUiThread {
                val cached = checkNotNull(model)
                assertFalse(cached.firstDrawn.value)
                screen.activity.setContent {
                    LumenaraTheme {
                        var home by remember { mutableStateOf(true) }
                        val saved = rememberSaveableStateHolder()
                        if (home) saved.SaveableStateProvider("home") {
                            Box(Modifier.fillMaxSize().drawWithContent {
                                drawContent()
                                gateAtFirstDraw.compareAndSet(null, cached.firstDrawn.value)
                            }) {
                                Home(cached, source, open = { home = false }, play = { _, _ -> home = false }, navigate = {})
                            }
                        } else Button(onClick = { home = true }) { Text("Return to cached Hub") }
                    }
                }
            }
            screen.waitUntil(10_000) { gateAtFirstDraw.get() != null }
            assertEquals("Cached shelves must be in the first populated draw", true, gateAtFirstDraw.get())
            for (tile in listOf(hasTestTag("live-channel-${channel.id}"), hasContentDescription(movie.title))) {
                screen.onNode(tile).performSemanticsAction(SemanticsActions.RequestFocus)
                screen.waitUntil(10_000) {
                    screen.onAllNodes(tile and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                // TV Material Surface handles remote OK; performClick injects a touch tap.
                screen.onNode(tile).assertIsFocused()
                screen.ok()
                screen.waitUntil(10_000) {
                    screen.onAllNodesWithText("Return to cached Hub").fetchSemanticsNodes().isNotEmpty()
                }
                screen.onNodeWithText("Return to cached Hub").performSemanticsAction(SemanticsActions.RequestFocus)
                screen.ok()
                screen.waitUntil(10_000) {
                    screen.onAllNodes(tile and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
            }
        } finally {
            screen.runOnUiThread {
                screen.activity.setContent {}
                model?.viewModelScope?.cancel()
            }
            runBlocking { prefs.set("recent_live", savedRecent); repo.remove(source) }
        }
    }
}
