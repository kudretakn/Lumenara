package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Item
import dev.lumenara.data.Repository
import dev.lumenara.data.Source
import dev.lumenara.design.LumenaraTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ListLoadingTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun delayedDatabaseResultDoesNotShowEmptyOrTakeFocus() {
        delayedCategories(empty = false)
    }

    @Test fun emptyStateWaitsForFirstDatabaseEmission() {
        delayedCategories(empty = true)
    }

    @Test fun completedCatalogResultDoesNotShowEmptyOrTakeFocusBeforeEmission() {
        delayedCategories(empty = false, derived = true)
    }

    @Test fun completedEmptyCatalogWaitsForFirstDatabaseEmission() {
        delayedCategories(empty = true, derived = true)
    }

    private fun delayedCategories(empty: Boolean, derived: Boolean = false) {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "loading-${System.nanoTime()}"
        val release = CompletableDeferred<Unit>()
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), if (empty) 0 else 1))
                if (!empty) repo.library.save(listOf(
                    Item("$source-1", source, "Channel", "Haber", "LIVE", "", "", "channel", 1, "")
                ))
                if (derived) prepareTestCatalog(repo, source)
                org.junit.Assert.assertEquals(derived, repo.library.catalogReady(source).first())
            }
            // Gate the real DB flow deterministically: the UI must remain loading
            // for arbitrarily slow database reads, regardless of device speed.
            val categories = repo.liveCategoryCounts(source)
                .onStart { release.await() }.map { providerCategories(it) }
            screen.runOnUiThread {
                screen.activity.setContent {
                    LumenaraTheme {
                        val entries by categories.collectAsLoadingState()
                        CategoryGrid("Canlı TV", entries) {}
                    }
                }
            }
            // The lazy skeleton only composes visible cells; several placeholders must show.
            val skeletons = screen.onAllNodesWithTag("list-loading").fetchSemanticsNodes().size
            org.junit.Assert.assertTrue("Loading skeleton should fill at least one row, was $skeletons", skeletons >= 6)
            screen.onNodeWithText("İçerik yok.").assertDoesNotExist()
            screen.onNodeWithText("Geri").assertDoesNotExist()
            // Focus may rest on the top bar (search) while loading; the list itself must not take it.
            screen.onAllNodes(isFocused() and hasTestTag("category-Haber")).assertCountEquals(0)
            screen.onAllNodes(isFocused() and hasTestTag("list-loading")).assertCountEquals(0)
            screen.runOnIdle { release.complete(Unit) }
            fun assertResult() {
                val target = if (empty) hasText("Geri") else hasTestTag("category-Haber")
                screen.waitUntil(10_000) {
                    screen.onAllNodesWithTag("list-loading").fetchSemanticsNodes().isEmpty() &&
                        screen.onAllNodes(target and isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                if (empty) {
                    screen.onNodeWithText("İçerik yok.").assertIsDisplayed()
                    screen.onNodeWithText("Geri").assertIsFocused()
                } else {
                    screen.onNodeWithText("İçerik yok.").assertDoesNotExist()
                    screen.onNodeWithTag("category-Haber").assertIsFocused()
                }
            }
            // The first real fallback result is usable before derivation, including
            // a confirmed empty result. Derivation must preserve the result and focus.
            assertResult()
            if (!derived) {
                runBlocking { prepareTestCatalog(repo, source) }
                screen.waitForIdle()
                assertResult()
            }
        } finally {
            release.complete(Unit)
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }
}
