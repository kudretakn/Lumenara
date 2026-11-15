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

    private fun delayedCategories(empty: Boolean) {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "loading-${System.nanoTime()}"
        val release = CompletableDeferred<Unit>()
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), if (empty) 0 else 1))
                if (!empty) repo.library.save(listOf(
                    Item("$source-1", source, "Channel", "Haber", "LIVE", "", "", "channel", 1, "")
                ))
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
            screen.onAllNodesWithTag("list-loading").assertCountEquals(8)
            screen.onNodeWithText("İçerik yok.").assertDoesNotExist()
            screen.onNodeWithText("Geri").assertDoesNotExist()
            screen.runOnIdle { release.complete(Unit) }
            screen.waitUntil(10_000) {
                screen.onAllNodesWithTag("list-loading").fetchSemanticsNodes().isEmpty()
            }
            if (empty) {
                screen.onNodeWithText("İçerik yok.").assertIsDisplayed()
                screen.onNodeWithText("Geri").assertIsFocused()
            } else {
                screen.onNodeWithText("İçerik yok.").assertDoesNotExist()
                screen.onNodeWithTag("category-Haber").assertIsFocused()
            }
        } finally {
            release.complete(Unit)
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }
}
