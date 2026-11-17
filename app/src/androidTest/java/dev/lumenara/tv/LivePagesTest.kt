package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.lumenara.data.Item
import dev.lumenara.data.Repository
import dev.lumenara.data.Source
import dev.lumenara.design.LumenaraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LivePagesTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun firstOpenPublishesOnePageBeforeTheTotalIsLoaded() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "live-first-page-${System.nanoTime()}"
        val rows = List(4 * LIVE_PAGE_SIZE) { index ->
            val title = "Channel %04d".format(java.util.Locale.ROOT, index)
            Item("$source-$index", source, title, "News", "LIVE", "", "", title.lowercase(), 1, "")
        }
        var pages: LivePages? = null
        var initialRows: Int? = null
        var initialCountLoaded = true
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    val state = rememberLivePages(model, source)
                    // Observe publication here; SideEffect itself does not subscribe to
                    // snapshot reads made inside its callback.
                    val firstPageLoaded = state.firstPageLoaded
                    val countLoaded = state.countLoaded
                    val loadedRows = state.pages.values.sumOf { it.size }
                    SideEffect {
                        pages = state
                        if (initialRows == null && firstPageLoaded) {
                            initialRows = loadedRows
                            initialCountLoaded = countLoaded
                        }
                    }
                }
            }
            screen.waitUntil(10_000) { initialRows != null && pages?.countLoaded == true }
            screen.runOnIdle {
                assertFalse("COUNT must start after the first page is published", initialCountLoaded)
                assertEquals(LIVE_PAGE_SIZE, initialRows)
                assertEquals(rows.size, pages?.count)
                assertEquals(rows.first().id, pages?.get(0)?.id)
                assertTrue(checkNotNull(pages).pages.values.sumOf { it.size } <= 5 * LIVE_PAGE_SIZE)
            }
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }

    @Test fun distantReturnLoadsOnlyNearbyPagesAndRestoresFocus() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "live-pages-${System.nanoTime()}"
        val anchorIndex = 3 * LIVE_PAGE_SIZE
        val endIndex = anchorIndex + LIVE_PAGE_SIZE
        val previousIndex = anchorIndex - LIVE_PAGE_SIZE
        val anchorPage = anchorIndex / LIVE_PAGE_SIZE
        val endPage = endIndex / LIVE_PAGE_SIZE
        val rows = (0..endIndex).map { index ->
            val title = "Channel %03d".format(java.util.Locale.ROOT, index)
            Item("$source-$index", source, title, "Haber", "LIVE", "", "", title.lowercase(), 1, "")
        }
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
            }
            var pages: LivePages? = null
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    val state = rememberLivePages(model, source, returnId = rows[anchorIndex].id)
                    SideEffect { pages = state }
                }
            }
            screen.waitUntil(10_000) { pages?.get(anchorIndex)?.id == rows[anchorIndex].id }
            screen.runOnIdle {
                assertEquals(anchorIndex, pages?.anchor)
                assertTrue(checkNotNull(pages).pages.keys.all { it in (anchorPage - 2)..(anchorPage + 2) })
                assertTrue(checkNotNull(pages).pages.values.sumOf { it.size } <= 5 * LIVE_PAGE_SIZE)
                checkNotNull(pages).focused(endIndex)
            }
            screen.waitUntil(10_000) {
                pages?.get(endIndex)?.id == rows[endIndex].id &&
                    pages?.pages?.keys?.all { it in (endPage - 2)..endPage } == true
            }
            // A prefix rename invalidates saved keysets. Absolute list indices must be
            // recomputed even when the current window began with a cached cursor.
            runBlocking { repo.library.save(listOf(rows[0].copy(title = "Z renamed"))) }
            screen.waitUntil(10_000) { pages?.get(anchorIndex)?.id == rows[anchorIndex + 1].id }
            runBlocking { repo.library.save(listOf(rows[0])) }
            screen.waitUntil(10_000) { pages?.get(anchorIndex)?.id == rows[anchorIndex].id }
            screen.runOnIdle {
                checkNotNull(pages).focused(anchorIndex)
            }
            screen.waitUntil(10_000) {
                pages?.get(previousIndex)?.id == rows[previousIndex].id &&
                    pages?.pages?.keys?.all { it in (anchorPage - 2)..(anchorPage + 2) } == true
            }
            screen.runOnIdle {
                checkNotNull(pages).focused(0)
            }
            screen.waitUntil(10_000) {
                pages?.get(0)?.id == rows[0].id && pages?.pages?.keys?.all { it in 0..2 } == true
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        LiveScreen(model, source, {}, { _, _ -> }, returnToChannel = rows[anchorIndex].id)
                    }
                }
            }
            screen.waitUntil(10_000) {
                screen.onAllNodes(hasTestTag("live-channel-${rows[anchorIndex].id}") and isFocused())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        var visible by remember { mutableStateOf(true) }
                        val saved = rememberSaveableStateHolder()
                        if (visible) saved.SaveableStateProvider("timeline") {
                            Timeline(model, source, { visible = false }, { _, _ -> }) {}
                        } else Button(onClick = { visible = true }) { Text("Return to timeline") }
                    }
                }
            }
            screen.waitUntil(10_000) {
                screen.onAllNodes(hasText(rows[0].title)).fetchSemanticsNodes().isNotEmpty()
            }
            screen.onNode(hasScrollToIndexAction()).performScrollToIndex(anchorIndex)
            screen.waitUntil(10_000) {
                screen.onAllNodes(hasText(rows[anchorIndex].title)).fetchSemanticsNodes().isNotEmpty()
            }
            // TV Material surfaces handle remote OK, not injected touch clicks.
            screen.onNode(hasText(rows[anchorIndex].title) and hasClickAction())
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .assertIsFocused()
            screen.ok()
            screen.onNodeWithText("Return to timeline")
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .assertIsFocused()
            screen.ok()
            screen.waitUntil(10_000) {
                screen.onAllNodes(hasText(rows[anchorIndex].title) and isFocused())
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }
}
