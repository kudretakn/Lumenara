package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
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

class SearchPlayerPagingTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun searchRowsLoadEvictAndRestoreWithRemoteFocus() = remoteWindow(search = true)

    @Test fun playerChannelRowsLoadEvictAndRestoreWithRemoteFocus() = remoteWindow(search = false)

    @Test fun searchFieldDownKeepsTheFirstResultFocusedWhenShelvesSeparate() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "search-field-focus-${System.nanoTime()}"
        val rows = listOf("LIVE", "MOVIE").flatMap { kind ->
            List(12) { index ->
                val title = "Film %04d".format(java.util.Locale.ROOT, index)
                Item("$source-$kind-$index", source, title, "Test", kind,
                    "", "", title.lowercase(), 1, "")
            }
        }
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme { SearchScreen(model, source, {}, { _, _ -> }) }
                }
            }
            screen.onNodeWithTag("search-input").performTextReplacement("Film")
            screen.waitUntil(10_000) {
                screen.onAllNodesWithTag("search-slot-Kanallar:0").fetchSemanticsNodes().isNotEmpty()
            }
            screen.dpad(Key.DirectionDown)
            screen.waitUntil(10_000) {
                screen.onAllNodes(hasTestTag("search-slot-Kanallar:0") and isFocused())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            screen.onNodeWithTag("channel-shelf-Kanallar").assertExists()
            screen.dpad(Key.DirectionRight)
            screen.onNodeWithTag("search-slot-Kanallar:1").assertIsFocused()
            screen.onNodeWithText("Devamını göster").assertDoesNotExist()
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }

    private fun remoteWindow(search: Boolean) {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "search-player-window-${System.nanoTime()}"
        val pageSize = 20
        val destination = 3 * pageSize + 4
        val rows = List(7 * pageSize) { index ->
            val title = "Film %04d".format(java.util.Locale.ROOT, index)
            Item("$source-$index", source, title, "Test", if (search) "MOVIE" else "LIVE",
                "", "", title.lowercase(), 1, "")
        }
        var pages: CatalogPages? = null
        fun tag(index: Int) = if (search) "search-slot-Filmler:$index" else "player-channel-slot-$index"
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        val state = rememberCatalogPages(model, source, if (search) "MOVIE" else "LIVE",
                            query = "film", pageSize = pageSize, columns = 1)
                        SideEffect { pages = state }
                        if (search) SearchPagingRow(model, listOf("Filmler" to state), "paging-search",
                            {}, {}, { _, _ -> })
                        else LazyColumn(Modifier.fillMaxSize()) { playerChannelItems(state) { _, _ -> } }
                    }
                }
            }
            screen.waitUntil(10_000) { pages?.countLoaded == true && pages?.get(0) != null }
            screen.onNodeWithText("Devamını göster").assertDoesNotExist()
            screen.onNodeWithTag(tag(0)).performSemanticsAction(SemanticsActions.RequestFocus)
                .assertIsFocused()
            for (index in 1..destination) {
                screen.dpad(if (search) Key.DirectionRight else Key.DirectionDown)
                screen.waitUntil(10_000) {
                    pages?.get(index)?.id == rows[index].id &&
                        screen.onAllNodes(hasTestTag(tag(index)) and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                screen.runOnIdle {
                    assertTrue(checkNotNull(pages).pages.size <= 5)
                    assertTrue(checkNotNull(pages).pages.values.sumOf { it.size } <= 5 * pageSize)
                }
            }
            screen.runOnIdle {
                assertNull("The early page must leave the Item window", checkNotNull(pages)[0])
                if (!search) assertTrue(checkNotNull(pages).loaded() is LiveChannelGroup)
            }
            for (index in destination - 1 downTo 0) {
                screen.dpad(if (search) Key.DirectionLeft else Key.DirectionUp)
                screen.waitUntil(10_000) {
                    pages?.get(index)?.id == rows[index].id &&
                        screen.onAllNodes(hasTestTag(tag(index)) and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
            }
            screen.onNodeWithTag(tag(0)).assertIsFocused()
            screen.onNodeWithText("Devamını göster").assertDoesNotExist()
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }
}
