package dev.lumenara.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.lumenara.design.LumenaraTheme
import org.junit.Rule
import org.junit.Test

class MigrationLoadingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingDatabaseHasVisibleTextAndIndeterminateProgress() {
        compose.setContent { LumenaraTheme { MigrationLoadingScreen() } }
        compose.onNodeWithTag("migration-loading").assertIsDisplayed()
        compose.onNodeWithText("Lumenara güncelleniyor\u2026").assertIsDisplayed()
        compose.onNodeWithTag("migration-progress").assertIsDisplayed()
    }
}
