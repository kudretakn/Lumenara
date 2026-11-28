package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.lumenara.design.FocusCard
import dev.lumenara.design.LumenaraTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Wp21SynchronizationTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun delayedShelfBecomesScrollableAndReceivesRemoteFocusBeforeActivation() {
        val rows = MutableStateFlow(emptyList<String>())
        val activated = AtomicBoolean(false)
        try {
            screen.runOnUiThread {
                screen.activity.setContent {
                    val current by rows.collectAsState()
                    LumenaraTheme {
                        LazyColumn(Modifier.fillMaxSize().testTag("wp21-list")) {
                            if (current.isEmpty()) item {
                                Box(Modifier.height(64.dp).testTag("wp21-loading"))
                            }
                            items(current) { id ->
                                FocusCard(onClick = { activated.set(true) },
                                    modifier = Modifier.fillMaxWidth().height(64.dp).testTag(id)) { }
                            }
                        }
                    }
                }
            }
            screen.wp21AwaitNode(hasTestTag("wp21-loading"))
            screen.onNodeWithTag("wp21-delayed-row").assertDoesNotExist()
            // Like a Room emission, the row arrives after the loading layout, not when the
            // parent first acquires focus. Window frames release it without a wall-time sleep.
            screen.runOnUiThread {
                val view = screen.activity.window.decorView
                view.postOnAnimation { view.postOnAnimation { rows.value = listOf("wp21-delayed-row") } }
            }
            screen.wp21ScrollTo("wp21-list", hasTestTag("wp21-delayed-row"))
            screen.wp21Focus(hasTestTag("wp21-delayed-row"))
            screen.wp21AwaitWindowFrames()
            screen.onNodeWithTag("wp21-delayed-row").assertIsFocused()
            screen.onNodeWithTag("wp21-loading").assertDoesNotExist()
            screen.ok()
            assertTrue("Remote OK must activate the delayed focused row", activated.get())
        } finally {
            screen.runOnUiThread { screen.activity.setContent { } }
        }
    }
}
