package dev.lumenara.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.lumenara.data.*
import dev.lumenara.design.LumenColors
import dev.lumenara.design.LumenaraTheme
import dev.lumenara.design.Spacing
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LivePresentationTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun await(matcher: SemanticsMatcher) {
        screen.waitUntil(10_000) { screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shot(name: String) {
        val test = InstrumentationRegistry.getInstrumentation()
        val file = File(test.targetContext.getExternalFilesDir(null), "screens/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        assertTrue(UiDevice.getInstance(test).takeScreenshot(file))
    }

    @Test fun logoFocusKeepsCaptionsStillAndSearchKeepsInputFocus() {
        val repo = Repository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = "live-presentation-${System.nanoTime()}"
        val rows = (1..8).map { number ->
            val title = "Spor Kanalı %02d".format(number)
            Item("$source-$number", source, title, "Spor", "LIVE", "", "", title.lowercase(), 1, "")
        }
        val feed = "$source-guide"
        try {
            runBlocking {
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
                repo.guide.put(Feed(feed, source, ""))
                val now = System.currentTimeMillis()
                rows.forEach { channel ->
                    repo.guide.map(Mapping(channel.id, feed, channel.id))
                    repo.guide.slots(listOf(Slot(feed, channel.id, now - 1_800_000, now + 1_800_000,
                        "Süper Lig: Galatasaray – Fenerbahçe", "", "super lig")))
                }
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        Box(Modifier.fillMaxSize().background(LumenColors.bg)
                            .padding(Spacing.screenHorizontal, Spacing.screenVertical)) {
                            LiveScreen(model, source, {}, { _, _ -> })
                        }
                    }
                }
            }
            screen.openCategory("Spor")
            await(hasTestTag("live-channel-${rows[0].id}") and isFocused())
            await(hasText("Süper Lig: Galatasaray – Fenerbahçe"))
            val second = screen.onNodeWithTag("live-channel-${rows[1].id}")
            val before = screen.onNodeWithText(rows[1].title, substring = true).fetchSemanticsNode().boundsInRoot
            second.performSemanticsAction(SemanticsActions.RequestFocus)
            second.assertIsFocused()
            val after = screen.onNodeWithText(rows[1].title, substring = true).fetchSemanticsNode().boundsInRoot
            assertEquals("Focus must leave the caption in place", before, after)
            val logoBounds = second.fetchSemanticsNode().boundsInRoot
            assertEquals("Only the 16:9 tile receives focus", 16f / 9f,
                logoBounds.width / logoBounds.height, .02f)
            screen.onNodeWithContentDescription("Kanal ara").assertExists()
            screen.onNodeWithContentDescription("Filtrele").assertExists()
            shot("live")

            screen.onNodeWithContentDescription("Kanal ara")
                .performSemanticsAction(SemanticsActions.RequestFocus)
            screen.ok()
            await(hasSetTextAction() and isFocused())
            screen.onNode(hasSetTextAction()).performTextInput("02")
            await(hasTestTag("live-channel-${rows[1].id}"))
            screen.onNode(hasSetTextAction()).assertIsFocused()
            screen.onNodeWithTag("live-channel-${rows[0].id}").assertDoesNotExist()
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { repo.remove(source) }
        }
    }

    @Test fun liveHintPersistsForThreeSessionsAndDoesNotCountChannelSwitches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val saved = runBlocking { prefs.text(LIVE_HINT_USES).first() }
        val source = "live-hint-${System.nanoTime()}"
        val rows = (1..2).map { number ->
            val title = "Hint Channel $number"
            Item("$source-$number", source, title, "Spor", "LIVE", "", "", title.lowercase(), 1, "")
        }
        val session = mutableIntStateOf(0)
        val selected = mutableStateOf(rows[0])
        val optionsOpened = mutableIntStateOf(0)
        var fullscreen: FullscreenState? = null
        try {
            runBlocking {
                prefs.set(LIVE_HINT_USES, "0")
                repo.library.save(Source(source, source, "", false, System.currentTimeMillis(), rows.size))
                repo.library.save(rows)
            }
            screen.runOnUiThread {
                val model = ViewModelProvider(screen.activity)[LibraryModel::class.java]
                screen.activity.setContent {
                    LumenaraTheme {
                        key(session.intValue) {
                            val state = rememberFullscreenState(selected.value)
                            SideEffect { fullscreen = state }
                            Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                                FullscreenOverlay(model, selected.value, rows, state,
                                    { item, _ -> selected.value = item }, { optionsOpened.intValue++ },
                                    { 0L }, { 0L }, null)
                            }
                        }
                    }
                }
            }
            for (use in 1..4) {
                val previousState = fullscreen
                if (use > 1) screen.runOnIdle { session.intValue++ }
                screen.waitUntil(10_000) { (use == 1 || fullscreen !== previousState) &&
                    fullscreen?.hintClaimed == true &&
                    runBlocking { prefs.text(LIVE_HINT_USES).first() } == minOf(use, 3).toString() }
                await(hasTestTag("fullscreen-banner"))
                if (use <= 3) screen.onNodeWithTag("live-first-use-hint").assertExists()
                else screen.onNodeWithTag("live-first-use-hint").assertDoesNotExist()
                if (use == 1) {
                    screen.runOnIdle { selected.value = rows[1] }
                    await(hasText(rows[1].title))
                    assertEquals("A channel switch shares the same onboarding use", "1",
                        runBlocking { prefs.text(LIVE_HINT_USES).first() })
                    screen.runOnIdle { fullscreen?.showBanner() }
                    screen.onNodeWithTag("live-first-use-hint").assertExists()
                    screen.onAllNodes(hasText("Program bilgisi yok")).assertCountEquals(0)
                    screen.onAllNodes(hasText("Sonra", substring = true)).assertCountEquals(0)
                }
            }
            screen.runOnIdle { fullscreen?.openGuide() }
            await(hasTestTag("live-channel-${rows[1].id}") and isFocused())
            screen.longOk()
            screen.onNodeWithTag("mini-guide").assertDoesNotExist()
            assertEquals("Long OK keeps the channel operations action", 1, optionsOpened.intValue)
        } finally {
            screen.runOnUiThread { screen.activity.setContent {} }
            runBlocking { prefs.set(LIVE_HINT_USES, saved); repo.remove(source) }
        }
    }
}
