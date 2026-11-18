package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
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

class WindowedGridTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun moviesPageDownAndBackWithoutLosingFocus() = navigate("MOVIE", "Filmler", 8, 80)
    @Test fun seriesPageDownAndBackWithoutLosingFocus() = navigate("SERIES", "Diziler", 8, 80)
    @Test fun livePageDownAndBackWithoutLosingFocus() = navigate("LIVE", "Canlı TV", LIVE_GRID_COLUMNS, LIVE_PAGE_SIZE)

    private fun navigate(kind: String, section: String, columns: Int, pageSize: Int) {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "window-grid-${System.nanoTime()}"
        val rows = List(9 * pageSize) { index ->
            val title = "Window %04d".format(java.util.Locale.ROOT, index)
            Item("$source-$index", source, title, "Test", kind, "", "", title.lowercase(), 1, "")
        }
        val tag = if (kind == "LIVE") "live-channels" else "poster-grid"
        fun focused(index: Int) = screen.onAllNodes(hasContentDescription(rows[index].title) and isFocused())
            .fetchSemanticsNodes().isNotEmpty()
        fun window(): List<Int> = screen.onNodeWithTag(tag).fetchSemanticsNode()
            .config[PagingWindowKey].split(':').map(String::toInt)
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
                prepareTestCatalog(repo, source)
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent { LumenaraTheme { Browse(model, section, source, {}, { _, _ -> }) } }
            }
            screen.openCategory(if (kind == "LIVE") "Tüm kanallar" else "Tümü")
            screen.waitUntil(10_000) { focused(0) }
            screen.onNodeWithText("Devamını göster").assertDoesNotExist()
            fun assertHorizontalRow(first: Int) {
                screen.waitForIdle()
                val position = screen.onNodeWithTag(tag).fetchSemanticsNode().config[ScrollPositionKey]
                for (column in 1 until columns) {
                    screen.dpad(Key.DirectionRight)
                    screen.waitUntil(10_000) { focused(first + column) }
                    screen.waitForIdle()
                    assertEquals("DPAD_RIGHT moved $tag vertically at ${first + column}", position,
                        screen.onNodeWithTag(tag).fetchSemanticsNode().config[ScrollPositionKey])
                }
                for (column in columns - 2 downTo 0) {
                    screen.dpad(Key.DirectionLeft)
                    screen.waitUntil(10_000) { focused(first + column) }
                    screen.waitForIdle()
                    assertEquals("DPAD_LEFT moved $tag vertically at ${first + column}", position,
                        screen.onNodeWithTag(tag).fetchSemanticsNode().config[ScrollPositionKey])
                }
            }
            val steps = 5 * pageSize / columns
            for (row in 1..steps) {
                screen.dpad(Key.DirectionDown)
                screen.waitUntil(10_000) { focused(row * columns) }
                // Exercise a lower visible row, a page boundary and a distant evicted window.
                if (row == 2 || row == pageSize / columns + 1 || row == steps) {
                    assertHorizontalRow(row * columns)
                }
                if (row == pageSize / columns + 1) {
                    // The first row past the first page must receive the next DPAD focus.
                    assertTrue(focused(pageSize + columns))
                    screen.onNodeWithText("Devamını göster").assertDoesNotExist()
                }
            }
            screen.waitUntil(10_000) { window()[0] > 0 }
            window().let { metadata ->
                assertTrue("Early pages must leave the Item window", metadata[0] > 0)
                assertTrue("At most five pages", metadata[2] <= 5)
                assertTrue("Bounded Item rows", metadata[3] <= 5 * pageSize)
            }
            for (row in steps - 1 downTo 0) {
                screen.dpad(Key.DirectionUp)
                screen.waitUntil(10_000) { focused(row * columns) }
            }
            screen.waitUntil(10_000) { window()[0] == 0 }
            assertTrue("Evicted first row is re-queried with focus intact", focused(0))
            screen.onNodeWithText("Devamını göster").assertDoesNotExist()
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }
}
