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
        val channels = List(3) { channel.copy(id = "${channel.id}-$it", title = "${channel.title} $it") }
        val movies = List(3) { movie.copy(id = "${movie.id}-$it", title = "${movie.title} $it") }
        val savedRecent = runBlocking { prefs.text("recent_live").first() }
        val gateAtFirstDraw = AtomicReference<Boolean>()
        var model: LibraryModel? = null
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), 6))
                repo.library.save(channels + movies)
                movies.forEachIndexed { index, item ->
                    repo.library.save(Mark(item.id, position = 10, duration = 100, updated = System.currentTimeMillis() - index))
                }
                prepareTestCatalog(repo, source)
                channels.reversed().forEach { prefs.recordLive(it.id) }
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
            assertEquals("Sized shelf slots must release IO on their first draw", true, gateAtFirstDraw.get())
            // Later tiles must retain their saved admission state and their own
            // return-focus flag, rather than briefly becoming placeholders again.
            for (tile in listOf(hasTestTag("live-channel-${channels.last().id}"), hasContentDescription(movies.last().title))) {
                screen.waitUntil(10_000) { screen.onAllNodes(tile).fetchSemanticsNodes().isNotEmpty() }
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
