package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
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

    private fun assertTwoLineName(tag: String, name: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        screen.onNode(hasText(name) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(1))
    }

    private fun assertSixRows(gridTag: String, tags: List<String>) {
        screen.waitUntil(10_000) {
            screen.onAllNodesWithTag(tags[23]).fetchSemanticsNodes().isNotEmpty()
        }
        // Measure resting cards; the focused card deliberately scales to 1.06.
        screen.onAllNodes(isFocusable() and hasClickAction() and
            !hasAnyAncestor(hasTestTag(gridTag))).onFirst()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        screen.waitForIdle()
        val viewport = screen.onNodeWithTag(gridTag).fetchSemanticsNode().boundsInRoot
        val density = screen.activity.resources.displayMetrics.density
        val cards = tags.take(24).map {
            screen.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        cards.forEach {
            assertEquals(60f, it.height / density, 1f)
            assertEquals((viewport.width / density - 30f) / 4f, it.width / density, 1f)
            assertTrue(it.top >= viewport.top && it.bottom <= viewport.bottom)
        }
        cards.chunked(4).forEach { row ->
            row.forEach { assertEquals(row.first().top, it.top, density) }
            row.zipWithNext().forEach { (left, right) ->
                assertEquals(10f, (right.left - left.right) / density, 1f)
            }
        }
        assertEquals(10f, (cards[4].top - cards[0].bottom) / density, 1f)
    }

    @Test fun categoriesFitFourColumnsAndSixRowsInsideSafeArea() {
        val entries = List(42) {
            CategoryEntry("$it", if (it == 0) "TR ➾ Komedi - Romantik - Aile ve Çocuk Kanalları" else "Kategori $it", it + 1)
        }
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
        assertTwoLineName("category-${entries.first().name}", entries.first().name)
        screen.onNode(hasText("1") and hasAnyAncestor(hasTestTag("category-${entries.first().name}")),
            useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun categoriesStayStillAcrossLowerAndDistantRows() {
        val entries = List(240) { CategoryEntry("$it", "Kategori $it", it + 1) }
        screen.runOnUiThread {
            screen.activity.setContent {
                LumenaraTheme {
                    Box(Modifier.requiredSize(960.dp, 540.dp)
                        .padding(Spacing.screenHorizontal, Spacing.screenVertical)) {
                        CategoryGrid("Categories", entries) {}
                    }
                }
            }
        }
        fun focused(index: Int) = screen.onAllNodes(hasTestTag("category-Kategori $index") and isFocused())
            .fetchSemanticsNodes().isNotEmpty()
        fun position() = screen.onNodeWithTag("category-grid").fetchSemanticsNode().config[ScrollPositionKey]
        screen.waitUntil(10_000) { focused(0) }
        for (row in 1..40) {
            screen.dpad(Key.DirectionDown)
            screen.waitUntil(10_000) { focused(row * 4) }
            if (row == 5 || row == 40) {
                screen.waitForIdle()
                val before = position()
                for (column in 1..3) {
                    screen.dpad(Key.DirectionRight)
                    screen.waitUntil(10_000) { focused(row * 4 + column) }
                    screen.waitForIdle()
                    assertEquals("Category row shifted on RIGHT", before, position())
                }
                for (column in 2 downTo 0) {
                    screen.dpad(Key.DirectionLeft)
                    screen.waitUntil(10_000) { focused(row * 4 + column) }
                    screen.waitForIdle()
                    assertEquals("Category row shifted on LEFT", before, position())
                }
            }
        }
        assertTrue("DOWN must still scroll", position().substringBefore(':').toInt() > 0)
    }

    @Test fun channelsFitFourColumnsAndSixRowsInsideSafeArea() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "dense-grid-${System.nanoTime()}"
        val rows = List(42) { index ->
            val title = if (index == 0) "AF: Çok Uzun Kanal Adı Spor Sinema ve Belgesel Yayınları"
                else "Kanal %03d".format(java.util.Locale.ROOT, index)
            Item("$source-$index", source, title, "Test", "LIVE", "", "", title.lowercase(), 1, "")
        }
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
                prepareTestCatalog(repo, source)
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
            val firstTag = "live-channel-${rows.first().id}"
            assertTwoLineName(firstTag, rows.first().title)
            screen.onNode(hasText("1") and hasAnyAncestor(hasTestTag(firstTag)),
                useUnmergedTree = true).assertIsDisplayed()
            screen.onNode(hasText("1 · ${rows.first().title}") and hasAnyAncestor(hasTestTag(firstTag)),
                useUnmergedTree = true).assertDoesNotExist()
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }

    @Test fun searchFitsFiveWiderPortraitCardsInsideSafeArea() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "dense-search-${System.nanoTime()}"
        val rows = List(80) { index ->
            val title = "Film %03d".format(java.util.Locale.ROOT, index)
            Item("$source-$index", source, title, "Test", "MOVIE", "", "", title.lowercase(), 1, "")
        }
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
                prepareTestCatalog(repo, source)
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        Box(Modifier.requiredSize(960.dp, 540.dp)
                            .padding(Spacing.screenHorizontal, Spacing.screenVertical)) {
                            SearchScreen(model, source, {}, { _, _ -> })
                        }
                    }
                }
            }
            screen.onNodeWithTag("search-input").performTextReplacement("Film")
            screen.waitUntil(10_000) {
                screen.onAllNodesWithTag("search-artwork-${rows[4].id}").fetchSemanticsNodes().isNotEmpty()
            }
            val viewport = screen.onNodeWithTag("search-shelf-Filmler").fetchSemanticsNode().boundsInRoot
            val density = screen.activity.resources.displayMetrics.density
            rows.take(5).forEach { item ->
                val card = screen.onNodeWithTag("search-artwork-${item.id}").assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                assertEquals("Arama kartı genişliği", 140f, card.width / density, 1f)
                assertEquals("Arama kartı yüksekliği", 210f, card.height / density, 1f)
                assertTrue("Beş kart ilk satırda tamamen görünmeli",
                    card.left >= viewport.left && card.right <= viewport.right &&
                        card.top >= viewport.top && card.bottom <= viewport.bottom)
            }
            screen.onNodeWithTag("search-slot-Filmler:0")
                .performSemanticsAction(SemanticsActions.RequestFocus)
            screen.waitForIdle()
            val before = screen.onNodeWithTag("search-results").fetchSemanticsNode().config[ScrollPositionKey]
            for (index in 1..15) {
                screen.dpad(Key.DirectionRight)
                screen.waitUntil(10_000) {
                    screen.onAllNodes(hasTestTag("search-slot-Filmler:$index") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screen.waitForIdle()
                assertEquals("Search shelf moved vertically", before,
                    screen.onNodeWithTag("search-results").fetchSemanticsNode().config[ScrollPositionKey])
            }
            for (index in 14 downTo 0) {
                screen.dpad(Key.DirectionLeft)
                screen.waitUntil(10_000) {
                    screen.onAllNodes(hasTestTag("search-slot-Filmler:$index") and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screen.waitForIdle()
                assertEquals("Shelf moved vertically on LEFT", before,
                    screen.onNodeWithTag("search-results").fetchSemanticsNode().config[ScrollPositionKey])
            }
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }

    @Test fun hubShelfStaysStillWhenHorizontalScrollingRevealsDifferentTitles() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "stable-hub-${System.nanoTime()}"
        val rows = List(16) { index ->
            val title = if (index % 2 == 0) "Film $index" else "A long film title that occupies two caption lines $index"
            Item("$source-$index", source, title, "Test", "MOVIE", "", "", title.lowercase(), 1, "")
        }
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
                prepareTestCatalog(repo, source)
                rows.forEachIndexed { index, item ->
                    repo.updateMark(item.id) { it.copy(position = 10, duration = 100, updated = 1000L - index) }
                }
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        Box(Modifier.requiredSize(960.dp, 360.dp)
                            .padding(Spacing.screenHorizontal, Spacing.screenVertical)) {
                            Home(model, source, {}, { _, _ -> }, navigate = {})
                        }
                    }
                }
            }
            screen.waitUntil(10_000) {
                screen.onAllNodesWithTag("shelf-Kaldığın yerden devam et").fetchSemanticsNodes().isNotEmpty()
            }
            screen.onNodeWithContentDescription(rows.first().title)
                .performSemanticsAction(SemanticsActions.RequestFocus)
            screen.waitForIdle()
            val before = screen.onNodeWithTag("hub").fetchSemanticsNode().config[ScrollPositionKey]
            for (index in 1..10) {
                screen.dpad(Key.DirectionRight)
                screen.waitUntil(10_000) {
                    screen.onAllNodes(hasContentDescription(rows[index].title) and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screen.waitForIdle()
                assertEquals("Hub shelf moved vertically", before,
                    screen.onNodeWithTag("hub").fetchSemanticsNode().config[ScrollPositionKey])
            }
            for (index in 9 downTo 0) {
                screen.dpad(Key.DirectionLeft)
                screen.waitUntil(10_000) {
                    screen.onAllNodes(hasContentDescription(rows[index].title) and isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screen.waitForIdle()
                assertEquals("Shelf moved vertically on LEFT", before,
                    screen.onNodeWithTag("hub").fetchSemanticsNode().config[ScrollPositionKey])
            }
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }
}
