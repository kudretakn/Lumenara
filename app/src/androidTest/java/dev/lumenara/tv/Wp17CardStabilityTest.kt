package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.tv.material3.Text
import dev.lumenara.design.LumenaraTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Counts the actual production composable bodies using compiler-generated trace markers. */
@OptIn(InternalComposeTracingApi::class)
class Wp17CardStabilityTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun freshEqualCardModelsSkipPosterAndHubTileBodies() {
        val poster = AtomicInteger()
        val hub = AtomicInteger()
        val tracer = object : CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.startsWith("dev.lumenara.tv.Wp17PosterTile (")) poster.incrementAndGet()
                if (info.startsWith("dev.lumenara.tv.HubShelfTile (")) hub.incrementAndGet()
            }
            override fun traceEventEnd() = Unit
        }
        lateinit var emission: MutableIntState
        val click: () -> Unit = {}
        val focus: (Boolean) -> Unit = {}
        val hubFocus: () -> Unit = {}
        val artwork: @Composable () -> Unit = { Box(Modifier.size(20.dp)) }
        val hubContent: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = { Text("Artwork") }
        try {
            screen.runOnUiThread {
                emission = mutableIntStateOf(0)
                Composer.setTracer(tracer)
                screen.activity.setContent {
                    LumenaraTheme {
                        val revision = emission.intValue
                        // A distinct instance on every parent emission; equality must skip the cards.
                        val card = Wp17PosterCard("id", if (revision < 2) "Film" else "Changed film", "Film", "2025")
                        Column {
                            Text("Emission $revision")
                            Wp17PosterTile(card, click, focus, Modifier.width(120.dp), artwork)
                            HubShelfTile(card, click, hubFocus, Modifier.width(120.dp), hubContent)
                        }
                    }
                }
            }
            screen.waitForIdle()
            val posterBefore = poster.get()
            val hubBefore = hub.get()
            assertTrue("Compiler trace markers must be present for the poster body", posterBefore > 0)
            assertTrue("Compiler trace markers must be present for the Hub tile body", hubBefore > 0)
            screen.runOnIdle { emission.intValue = 1 }
            screen.waitForIdle()
            assertEquals("Equal poster emission must skip its body", posterBefore, poster.get())
            assertEquals("Equal Hub emission must skip its body", hubBefore, hub.get())
            screen.runOnIdle { emission.intValue = 2 }
            screen.waitForIdle()
            assertTrue("Visible changes must update the poster", poster.get() > posterBefore)
            assertTrue("Visible changes must update the Hub tile", hub.get() > hubBefore)
        } finally {
            screen.runOnUiThread {
                screen.activity.setContent {}
                Composer.setTracer(null)
            }
        }
    }
}
