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

    @Test fun distantReturnLoadsOnlyNearbyPagesAndRestoresFocus() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "live-pages-${System.nanoTime()}"
        val rows = (0 until 321).map { index ->
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
                    val state = rememberLivePages(model, source, returnId = rows[240].id)
                    SideEffect { pages = state }
                }
            }
            screen.waitUntil(10_000) { pages?.get(240)?.id == rows[240].id }
            screen.runOnIdle {
                assertEquals(240, pages?.anchor)
                assertTrue(checkNotNull(pages).pages.keys.all { it in 2..4 })
                assertTrue(checkNotNull(pages).pages.values.sumOf { it.size } <= 3 * LIVE_PAGE_SIZE)
                checkNotNull(pages).focused(0)
            }
            screen.waitUntil(10_000) {
                pages?.get(0)?.id == rows[0].id && pages?.pages?.keys?.all { it in 0..1 } == true
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        LiveScreen(model, source, {}, { _, _ -> }, returnToChannel = rows[240].id)
                    }
                }
            }
            screen.waitUntil(10_000) {
                screen.onAllNodes(hasTestTag("live-channel-${rows[240].id}") and isFocused())
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
            screen.onNode(hasScrollToIndexAction()).performScrollToIndex(240)
            screen.waitUntil(10_000) {
                screen.onAllNodes(hasText(rows[240].title)).fetchSemanticsNodes().isNotEmpty()
            }
            // TV Material surfaces handle remote OK, not injected touch clicks.
            screen.onNode(hasText(rows[240].title) and hasClickAction())
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .assertIsFocused()
            screen.ok()
            screen.onNodeWithText("Return to timeline")
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .assertIsFocused()
            screen.ok()
            screen.waitUntil(10_000) {
                screen.onAllNodes(hasText(rows[240].title) and isFocused())
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }
}
