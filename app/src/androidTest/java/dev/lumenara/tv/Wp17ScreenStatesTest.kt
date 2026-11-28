package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.lumenara.design.LumenaraTheme
import org.junit.Rule
import org.junit.Test

class Wp17ScreenStatesTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun loadingErrorRetryEmptyAndReadyRemainDpadReachable() {
        lateinit var state: MutableState<ScreenUiState<List<CategoryEntry>>>
        try {
            screen.runOnUiThread {
                state = mutableStateOf(ScreenUiState.Loading)
                screen.activity.setContent {
                    LumenaraTheme {
                        when (val value = state.value) {
                            ScreenUiState.Loading -> CategoryGrid("Films", null) {}
                            is ScreenUiState.Error -> Wp17ScreenError(retry = { state.value = ScreenUiState.Ready(emptyList()) })
                            is ScreenUiState.Ready -> CategoryGrid("Films", value.value) {}
                        }
                    }
                }
            }
            screen.onAllNodesWithTag("list-loading").onFirst().assertExists()
            screen.runOnIdle { state.value = ScreenUiState.Error("wp17_categories_IOException") }
            screen.waitUntil(5_000) {
                screen.onAllNodes(hasTestTag("screen-retry") and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            screen.onNodeWithTag("screen-error").assertIsDisplayed()
            screen.ok()
            screen.onNodeWithText("İçerik yok.").assertIsDisplayed()
            screen.waitUntil(5_000) {
                screen.onAllNodes(hasText("Geri") and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            screen.runOnIdle { state.value = ScreenUiState.Ready(listOf(CategoryEntry("all", "Tümü", 2))) }
            screen.waitUntil(5_000) {
                screen.onAllNodes(hasTestTag("category-Tümü") and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            screen.onNodeWithTag("list-loading").assertDoesNotExist()
            screen.onNodeWithTag("screen-error").assertDoesNotExist()
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
        }
    }
}
