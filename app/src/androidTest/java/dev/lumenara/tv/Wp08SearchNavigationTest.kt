package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Item
import dev.lumenara.data.Repository
import dev.lumenara.data.Source
import dev.lumenara.design.LumenaraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the same SaveableStateProvider removal as LibraryScreen, without media/network startup. */
class Wp08SearchNavigationTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun sixtiethMovieReturnsFromDetailWithSameIdOffsetAndFocus() = navigation("MOVIE")
    @Test fun sixtiethChannelReturnsFromPlaybackWithSameIdOffsetAndFocus() = navigation("LIVE")
    @Test fun changedRankReturnsToTheSameIdAtItsNewSlot() = navigation("MOVIE", removePreceding = true)
    @Test fun removedResultReturnsToFirstResult() = navigation("MOVIE", removeSelected = true)
    @Test fun removedShelfReturnsToSearchField() = navigation("MOVIE", removeAll = true)

    private fun navigation(kind: String, removeSelected: Boolean = false, removeAll: Boolean = false,
        removePreceding: Boolean = false) {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "wp08-navigation-${System.nanoTime()}"
        val shelf = if (kind == "LIVE") "Kanallar" else "Filmler"
        val rowTag = if (kind == "LIVE") "channel-shelf-$shelf" else "search-shelf-$shelf"
        val routeTag = if (kind == "LIVE") "wp08-playback" else "wp08-detail"
        val rows = List(140) { index ->
            val title = "Film %04d".format(java.util.Locale.ROOT, index)
            Item("$source-$index", source, title, "Test", kind, "", "", title.lowercase(), 1, "")
        }
        // Movie return also exercises a non-first shelf and a scrolled vertical viewport.
        val leading = if (kind == "MOVIE") List(12) { index ->
            val title = "Film Channel %04d".format(java.util.Locale.ROOT, index)
            Item("$source-live-$index", source, title, "Test", "LIVE", "", "", title.lowercase(), 1, "")
        } else emptyList()
        val catalog = leading + rows
        val firstShelf = if (leading.isNotEmpty()) "Kanallar" else shelf
        val firstItem = leading.firstOrNull() ?: rows[0]
        var opened: Item? = null
        fun position(tag: String) = screen.onNodeWithTag(tag).fetchSemanticsNode().config[ScrollPositionKey]
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), catalog.size))
                repo.library.save(catalog)
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    var away by remember { mutableStateOf(false) }
                    val holder = rememberSaveableStateHolder()
                    LumenaraTheme {
                        if (away) {
                            BackHandler { away = false }
                            Box(Modifier.fillMaxSize().testTag(routeTag))
                        } else holder.SaveableStateProvider("search") {
                            SearchScreen(model, source, { opened = it; away = true },
                                { item, _ -> opened = item; away = true })
                        }
                    }
                }
            }
            screen.onNodeWithTag("search-input").performTextReplacement("Film")
            screen.waitUntil(5_000) {
                screen.onAllNodesWithTag("search-artwork-${firstItem.id}").fetchSemanticsNodes().isNotEmpty()
            }
            // Leave the combined input row before jumping; no long DPAD loop in CI.
            screen.onNodeWithTag("search-slot-$firstShelf:0")
                .performSemanticsAction(SemanticsActions.RequestFocus)
            if (leading.isNotEmpty()) screen.onNodeWithTag("search-results").performScrollToIndex(1)
            screen.onNodeWithTag(rowTag).performScrollToIndex(55)
            screen.waitUntil(5_000) {
                screen.onAllNodesWithTag("search-artwork-${rows[59].id}").fetchSemanticsNodes().isNotEmpty()
            }
            screen.onNodeWithTag("search-slot-$shelf:59")
                .performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
            screen.onNodeWithTag(rowTag).performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                scroll(17f, 0f)
            }
            screen.waitForIdle()
            val horizontal = position(rowTag)
            assertFalse("Exercise a nonzero pixel offset", horizontal.endsWith(":0"))
            val vertical = position("search-results")
            screen.ok()
            screen.onNodeWithTag(routeTag).assertExists()
            screen.runOnIdle { assertEquals(rows[59].id, opened?.id) }
            if (removeSelected || removeAll || removePreceding) runBlocking {
                // Generation pruning is the production refresh deletion path.
                if (!removeAll) repo.library.save(catalog.filter {
                    it.id != rows[if (removePreceding) 0 else 59].id
                }.map { it.copy(generation = 2) })
                repo.library.prune(source, 2)
            }
            screen.back()
            if (removeAll) {
                screen.waitUntil(5_000) {
                    screen.onAllNodes(hasTestTag("search-input") and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                screen.onNodeWithText("Eşleşen içerik yok.").assertExists()
                return
            }
            val target = when {
                removeSelected -> 0
                removePreceding -> 58
                else -> 59
            }
            val targetItem = rows[if (removeSelected) 0 else 59]
            screen.waitUntil(5_000) {
                screen.onAllNodes(hasTestTag("search-slot-$shelf:$target") and isFocused())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            screen.onNodeWithTag("search-slot-$shelf:$target").assertContentDescriptionEquals(targetItem.title)
            screen.onNodeWithTag("search-artwork-${targetItem.id}").assertExists()
            if (!removeSelected) {
                val expectedHorizontal = if (removePreceding) {
                    val (first, offset) = horizontal.split(":").map { it.toInt() }
                    "${first - 1}:$offset"
                } else horizontal
                assertEquals("Horizontal viewport must survive return", expectedHorizontal, position(rowTag))
                assertEquals("Vertical viewport must survive return", vertical, position("search-results"))
                // A completed replacement query starts at the first result, never the old slot.
                screen.onNodeWithTag("search-input").performSemanticsAction(SemanticsActions.RequestFocus)
                screen.onNodeWithTag("search-input").performTextReplacement("Film 013")
                screen.waitUntil(5_000) {
                    screen.onAllNodesWithTag("search-artwork-${rows[130].id}").fetchSemanticsNodes().isNotEmpty()
                }
                screen.dpad(androidx.compose.ui.input.key.Key.DirectionDown)
                screen.waitUntil(5_000) {
                    screen.onAllNodes(hasTestTag("search-slot-$shelf:0") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screen.onNodeWithTag("search-slot-$shelf:0").assertContentDescriptionEquals(rows[130].title)
            }
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }
}
