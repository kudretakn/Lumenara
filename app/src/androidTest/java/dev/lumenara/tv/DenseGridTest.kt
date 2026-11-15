package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Item
import dev.lumenara.data.Repository
import dev.lumenara.data.Source
import dev.lumenara.design.LumenaraTheme
import dev.lumenara.design.Spacing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DenseGridTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun assertSixRows(gridTag: String, tags: List<String>) {
        screen.waitUntil(10_000) {
            screen.onAllNodesWithTag(tags[35]).fetchSemanticsNodes().isNotEmpty()
        }
        // Measure resting cards; the focused card deliberately scales to 1.06.
        screen.onAllNodes(isFocusable() and hasClickAction() and
            !hasAnyAncestor(hasTestTag(gridTag))).onFirst()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        screen.waitForIdle()
        val viewport = screen.onNodeWithTag(gridTag).fetchSemanticsNode().boundsInRoot
        val density = screen.activity.resources.displayMetrics.density
        val cards = tags.take(36).map {
            screen.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        cards.forEach {
            assertEquals(60f, it.height / density, 1f)
            assertTrue(it.top >= viewport.top && it.bottom <= viewport.bottom)
        }
        cards.chunked(6).forEach { row ->
            row.forEach { assertEquals(row.first().top, it.top, density) }
            row.zipWithNext().forEach { (left, right) ->
                assertEquals(10f, (right.left - left.right) / density, 1f)
            }
        }
        assertEquals(10f, (cards[6].top - cards[0].bottom) / density, 1f)
    }

    @Test fun categoriesFitSixColumnsAndSixRowsInsideSafeArea() {
        val entries = List(42) { CategoryEntry("$it", "Kategori $it", it + 1) }
        screen.runOnUiThread {
            screen.activity.setContent {
                LumenaraTheme {
                    Box(Modifier.requiredSize(960.dp, 540.dp)
                        .padding(Spacing.screenHorizontal, Spacing.screenVertical)) {
                        CategoryGrid("Canlı TV", entries) {}
                    }
                }
            }
        }
        assertSixRows("category-grid", entries.map { "category-${it.name}" })
    }

    @Test fun channelsFitSixColumnsAndSixRowsInsideSafeArea() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "dense-grid-${System.nanoTime()}"
        val rows = List(42) { index ->
            val title = "Kanal %03d".format(java.util.Locale.ROOT, index)
            Item("$source-$index", source, title, "Test", "LIVE", "", "", title.lowercase(), 1, "")
        }
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        Box(Modifier.requiredSize(960.dp, 540.dp)
                            .padding(Spacing.screenHorizontal, Spacing.screenVertical)) {
                            LiveScreen(model, source, {}, { _, _ -> }, returnToChannel = rows.first().id)
                        }
                    }
                }
            }
            assertSixRows("live-channels", rows.map { "live-channel-${it.id}" })
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }
}
