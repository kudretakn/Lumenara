package dev.lumenara.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HubAndAdultFilterTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private fun waitFor(matcher: SemanticsMatcher, present: Boolean = true) {
        try {
            screen.waitUntil(30_000) {
                screen.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() == present
            }
        } catch (failure: Throwable) {
            screen.onRoot().printToLog("HUB")
            throw failure
        }
    }

    private fun toggleAdult() {
        screen.openHubCard("Ayarlar")
        screen.onNodeWithTag("settings-group-İçerik (+18)").performClick()
        screen.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("show-adult"))
        screen.onNodeWithTag("show-adult").performSemanticsAction(SemanticsActions.RequestFocus)
        waitFor(hasTestTag("show-adult") and isFocused())
        screen.ok()
    }

    @Test fun hubCountsCategoriesAdultVisibilityAndRemoteReturnFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repo = Repository.get(context)
        val prefs = Preferences(context)
        val name = "Test · Hub ve +18"
        val savedAdult = runBlocking { prefs.showAdult.first() }
        val savedEngine = runBlocking { prefs.text("engine", "Media3").first() }
        val savedRecent = runBlocking { prefs.text("recent_live").first() }
        MockWebServer().use { server ->
            val video = instrumentation.context.assets.open("lumenara-test.ts").use { it.readBytes() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/list") return MockResponse().setBody("""
                        #EXTM3U
                        #EXTINF:-1 group-title="Haber",Hub Haber
                        ${server.url("/live/news.ts")}
                        #EXTINF:-1 group-title="Spor",Hub Spor
                        ${server.url("/live/sport.ts")}
                        #EXTINF:-1 group-title="For Adults",Hub Gizli Kanal
                        ${server.url("/live/adult.ts")}
                        #EXTINF:-1 group-title="Aksiyon",Hub Film
                        ${server.url("/movie/film.mp4")}
                        #EXTINF:-1 group-title="XXX",Hub Gizli Film
                        ${server.url("/movie/adult.mp4")}
                    """.trimIndent())
                    return MockResponse().setHeader("Content-Type", "video/mp2t")
                        .setBody(Buffer().write(video))
                }
            }
            server.start()
            try {
                val channels = runBlocking {
                    // Hub counts span every source; leftovers from earlier tests would change them.
                    repo.library.snapshot().forEach { repo.remove(it.id) }
                    prefs.setShowAdult(false)
                    prefs.set("engine", "Media3")
                    prefs.set("recent_live", "")
                    repo.add(name, SourceSpec(server.url("/list").toString(), true))
                    val source = repo.library.snapshot().single { it.name == name }
                    repo.library.browse(source.id, "LIVE", "", "", "", 20).first()
                }
                val source = channels.first().source
                val news = channels.single { it.category == "Haber" }
                val adult = channels.single { it.category == "For Adults" }
                runBlocking {
                    // Hidden history/favorites must not leak into special categories or Hub shelves.
                    prefs.recordLive(adult.id)
                    repo.updateMark(adult.id) { it.copy(favorite = true) }
                    val movie = repo.library.browse(source, "MOVIE", "XXX", "", "", 1).first().single()
                    repo.updateMark(movie.id) { it.copy(position = 10, duration = 100, updated = 1, listed = true) }
                    repo.guide.put(Feed("hub-guide", source, ""))
                    val now = System.currentTimeMillis()
                    repo.guide.slots(listOf(Slot("hub-guide", "adult", now - 60_000,
                        now + 3_600_000, "Gizli Program", "", "gizli program")))
                    repo.guide.map(Mapping(adult.id, "hub-guide", "adult"))
                    assertTrue(repo.recentLive(source).first().isEmpty())
                    assertTrue(repo.continueWatching(source).first().isEmpty())
                    assertTrue(repo.browse(source, "LIVE", "", "", "favorite", 20).first().isEmpty())
                    assertTrue(repo.nowAiring(source, now).first().isEmpty())
                    assertTrue(repo.searchPrograms(source, "gizli", now - 60_000, now).first().isEmpty())
                    assertEquals(listOf(news.id, channels.single { it.category == "Spor" }.id),
                        kotlinx.coroutines.withTimeout(10_000) {
                            repo.liveChannelOrder(source).first { it.size == 2 }
                        }.map { it.id })
                    assertEquals(2, repo.categoryCounts("LIVE", "").first().sumOf { it.count })
                    assertEquals(1, repo.categoryCounts("MOVIE", "").first().sumOf { it.count })
                }
                screen.returnToHub()
                waitFor(hasTestTag("hub-Canlı TV") and isFocused())
                // Clickable cards merge their caption into the tagged card's semantics.
                waitFor(hasTestTag("hub-Canlı TV") and hasText("2 kanal"))
                waitFor(hasTestTag("hub-Filmler") and hasText("1 film"))
                screen.onNodeWithTag("channel-shelf-Son kanal").assertDoesNotExist()
                screen.onNodeWithTag("shelf-Kaldığın yerden devam et").assertDoesNotExist()
                // The complete journey uses the focused Hub card and remote OK.
                screen.ok()
                waitFor(hasTestTag("category-grid"))
                screen.onNodeWithTag("category-For Adults").assertDoesNotExist()
                screen.onNodeWithTag("category-Favoriler").assertDoesNotExist()
                screen.onNodeWithTag("category-Son izlenenler").assertDoesNotExist()
                screen.openCategory("Haber")
                waitFor(hasTestTag("live-channel-${news.id}") and isFocused())
                screen.ok()
                waitFor(hasContentDescription("Tam ekran oynatıcı") and isFocused())
                screen.dpad(Key.DirectionLeft)
                waitFor(hasTestTag("mini-guide"))
                screen.onNodeWithContentDescription("For Adults").assertDoesNotExist()
                screen.onNodeWithTag("live-channel-${adult.id}").assertDoesNotExist()
                screen.back()
                screen.dpad(Key.Zero)
                waitFor(hasText("Kanal yok"))
                waitFor(hasText("Kanal yok"), false)
                screen.dpad(Key.Three)
                screen.ok()
                waitFor(hasText("Kanal yok"))
                waitFor(hasText("Kanal yok"), false)
                repeat(2) {
                    if (screen.onAllNodesWithContentDescription("Tam ekran oynatıcı").fetchSemanticsNodes().isNotEmpty()) screen.back()
                }
                waitFor(hasTestTag("live-channel-${news.id}") and isFocused())
                screen.back()
                waitFor(hasTestTag("category-Haber") and isFocused())
                screen.back()
                waitFor(hasTestTag("hub-Canlı TV") and isFocused())
                screen.openHubCard("Filmler")
                screen.onNodeWithTag("category-XXX").assertDoesNotExist()
                screen.openCategory("Tümü")
                waitFor(hasContentDescription("Hub Film"))
                screen.onNodeWithContentDescription("Hub Gizli Film").assertDoesNotExist()
                screen.openHubCard("Ara")
                screen.onNode(hasSetTextAction()).performTextReplacement("Gizli")
                waitFor(hasText("Eşleşen içerik yok."))
                screen.onNodeWithContentDescription(adult.title).assertDoesNotExist()
                screen.onNodeWithContentDescription("Hub Gizli Film").assertDoesNotExist()
                toggleAdult()
                waitFor(hasText("+18 içerikleri göster · Açık"))
                screen.returnToHub()
                waitFor(hasTestTag("hub-Canlı TV") and hasText("3 kanal"))
                waitFor(hasTestTag("hub-Filmler") and hasText("2 film"))
                screen.openHubCard("Canlı TV")
                screen.onNodeWithTag("category-grid").performScrollToNode(hasTestTag("category-For Adults"))
                waitFor(hasTestTag("category-For Adults"))
                val last = screen.onNodeWithTag("category-For Adults").fetchSemanticsNode().boundsInRoot
                val sport = screen.onNodeWithTag("category-Spor").fetchSemanticsNode().boundsInRoot
                assertTrue(last.top > sport.top || last.top == sport.top && last.left > sport.left)
                screen.openCategory("For Adults")
                waitFor(hasTestTag("live-channel-${adult.id}"))
                screen.openHubCard("Filmler")
                waitFor(hasTestTag("category-XXX"))
                val xxx = screen.onNodeWithTag("category-XXX").fetchSemanticsNode().boundsInRoot
                val action = screen.onNodeWithTag("category-Aksiyon").fetchSemanticsNode().boundsInRoot
                assertTrue(xxx.top > action.top || xxx.top == action.top && xxx.left > action.left)
                screen.openCategory("XXX")
                waitFor(hasContentDescription("Hub Gizli Film"))
                screen.openHubCard("Ara")
                waitFor(hasContentDescription(adult.title))
                waitFor(hasContentDescription("Hub Gizli Film"))
                // Toggle back too: an already populated library updates immediately.
                toggleAdult()
                waitFor(hasText("+18 içerikleri göster · Kapalı"))
                screen.openHubCard("Canlı TV")
                screen.onNodeWithTag("category-For Adults").assertDoesNotExist()
                runBlocking { assertTrue(repo.liveByIds(source, listOf(adult.id)).first().isEmpty()) }
                screen.returnToHub()
                val activity = screen.activity
                screen.back()
                waitFor(hasTestTag("exit-confirmation"))
                assertFalse(activity.isFinishing)
                waitFor(hasTestTag("exit-confirmation"), false)
                screen.back()
                waitFor(hasTestTag("exit-confirmation"))
                screen.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
                screen.waitUntil(30_000) { activity.isFinishing || activity.isDestroyed }
            } finally {
                runBlocking {
                    prefs.setShowAdult(savedAdult)
                    prefs.set("engine", savedEngine)
                    prefs.set("recent_live", savedRecent)
                    repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
                }
            }
        }
    }
}
