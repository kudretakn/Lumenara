package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.catalog.searchable
import dev.lumenara.data.Item
import dev.lumenara.data.Mark
import dev.lumenara.data.Preferences
import dev.lumenara.data.Repository
import dev.lumenara.data.Source
import dev.lumenara.design.LumenaraTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Wp17NavigationTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun hubGridAndSixtiethSearchResultReturnThroughRealDetailsWithFocusAndViewport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = Repository.get(context)
        val source = "wp17-navigation-${System.nanoTime()}"
        val category = "Wp17 remote films"
        val rows = List(96) { index ->
            val title = "Wp17 Remote %03d".format(java.util.Locale.ROOT, index)
            Item("$source-$index", source, title, category, "MOVIE", "", "", searchable(title), 1, "")
        }
        var model: LibraryModel? = null
        fun target(tag: String): SemanticsMatcher = if (tag.startsWith("poster-slot-")) {
            val index = tag.removePrefix("poster-slot-").toInt()
            hasContentDescription(rows[index].title) and hasAnyAncestor(hasTestTag(tag))
        } else hasTestTag(tag)
        fun focused(tag: String) = screen.onAllNodes(target(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        fun awaitFocus(tag: String) = screen.waitUntil(10_000) { focused(tag) }
        fun position(tag: String): String {
            val node = screen.onNodeWithTag(tag).fetchSemanticsNode()
            return screen.runOnIdle { node.config[ScrollPositionKey] }
        }
        fun window(tag: String): List<Int> {
            val node = screen.onNodeWithTag(tag).fetchSemanticsNode()
            return screen.runOnIdle { node.config[PagingWindowKey] }.split(':').map(String::toInt)
        }
        fun openDetailsAndReturn(tag: String, viewportTag: String) {
            val before = position(viewportTag)
            screen.ok()
            awaitFocus("details-play")
            screen.onNodeWithTag("details-content").assertIsDisplayed()
            screen.back()
            awaitFocus(tag)
            assertEquals("Return must preserve the viewport offset", before,
                position(viewportTag))
        }
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
                repo.library.save(Mark(rows[0].id, position = 10, duration = 100, updated = System.currentTimeMillis()))
                prepareTestCatalog(repo, source)
            }
            screen.runOnUiThread {
                model = LibraryModel(dagger.Lazy { repo }, Preferences(context))
                screen.activity.setContent { LumenaraTheme { LibraryScreen(checkNotNull(model)) } }
            }
            screen.waitUntil(10_000) { screen.onAllNodesWithTag("hub").fetchSemanticsNodes().isNotEmpty() }
            if (screen.onAllNodesWithTag("hub-source-selector").fetchSemanticsNodes().isNotEmpty()) {
                screen.onNodeWithTag("hub-source-selector").performScrollToNode(hasText(source))
                val sourceChip = hasText(source) and hasAnyAncestor(hasTestTag("hub-source-selector"))
                screen.onNode(sourceChip).performSemanticsAction(SemanticsActions.RequestFocus)
                screen.waitUntil(10_000) { screen.onAllNodes(sourceChip and isFocused()).fetchSemanticsNodes().isNotEmpty() }
                screen.ok()
            }
            val resumeShelf = "shelf-Kaldığın yerden devam et"
            screen.waitUntil(10_000) { screen.onAllNodesWithTag(resumeShelf).fetchSemanticsNodes().isNotEmpty() }
            screen.onNodeWithTag("hub").performScrollToNode(hasTestTag(resumeShelf))
            val resumeTile = hasContentDescription(rows[0].title) and hasAnyAncestor(hasTestTag(resumeShelf))
            screen.onNode(resumeTile).performSemanticsAction(SemanticsActions.RequestFocus)
            screen.waitUntil(10_000) { screen.onAllNodes(resumeTile and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            screen.ok()
            awaitFocus("details-play")
            runBlocking { repo.library.save(Mark(rows[0].id)) }
            screen.back()
            awaitFocus("hub-Canlı TV") // The saved shelf disappeared while detail was visible.
            screen.openHubCard("Filmler")
            screen.openCategory(category)
            awaitFocus("poster-slot-0")
            repeat(10) { row -> screen.dpad(Key.DirectionDown); awaitFocus("poster-slot-${(row + 1) * 8}") }
            repeat(3) { column -> screen.dpad(Key.DirectionRight); awaitFocus("poster-slot-${81 + column}") }
            screen.onNode(target("poster-slot-83")).assertContentDescriptionEquals(rows[83].title)
            val gridWindow = window("poster-grid")
            assertTrue(gridWindow[2] <= 5)
            assertTrue(gridWindow[3] <= 5 * 80)
            openDetailsAndReturn("poster-slot-83", "poster-grid")
            screen.back() // Category list.
            screen.back() // Hub, where the activated route card must regain focus.
            awaitFocus("hub-Filmler")
            screen.openHubCard("Ara")
            screen.onNode(hasSetTextAction()).performTextReplacement("Wp17 Remote")
            screen.waitUntil(10_000) { screen.onAllNodesWithTag("search-slot-Filmler:0").fetchSemanticsNodes().isNotEmpty() }
            screen.dpad(Key.DirectionDown)
            awaitFocus("search-slot-Filmler:0")
            screen.waitUntil(10_000) {
                screen.onAllNodes(hasTestTag("search-shelf-Filmler") and
                    SemanticsMatcher.expectValue(Wp08SearchResultCountKey, rows.size)).fetchSemanticsNodes().isNotEmpty()
            }
            screen.onNodeWithTag("search-shelf-Filmler").performScrollToIndex(55)
            screen.waitUntil(10_000) { screen.onAllNodesWithTag("search-artwork-${rows[58].id}").fetchSemanticsNodes().isNotEmpty() }
            screen.onNodeWithTag("search-slot-Filmler:58").performSemanticsAction(SemanticsActions.RequestFocus)
            awaitFocus("search-slot-Filmler:58")
            screen.dpad(Key.DirectionRight)
            awaitFocus("search-slot-Filmler:59")
            screen.onNodeWithTag("search-shelf-Filmler").performSemanticsAction(SemanticsActions.ScrollBy) { it(17f, 0f) }
            screen.waitForIdle()
            assertFalse("Exercise a nonzero search offset", position("search-shelf-Filmler").endsWith(":0"))
            screen.onNodeWithTag("search-slot-Filmler:59").assertContentDescriptionEquals(rows[59].title)
            val searchWindow = window("search-shelf-Filmler")
            assertTrue(searchWindow[2] <= 5)
            assertTrue(searchWindow[3] <= 5 * 20)
            openDetailsAndReturn("search-slot-Filmler:59", "search-shelf-Filmler")
            screen.back()
            awaitFocus("hub-Ara")
        } finally {
            screen.runOnUiThread {
                screen.activity.setContent {}
                model?.viewModelScope?.cancel()
            }
            runBlocking { repo.remove(source) }
        }
    }
}
