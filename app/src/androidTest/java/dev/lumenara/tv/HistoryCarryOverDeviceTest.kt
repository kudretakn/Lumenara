package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class HistoryCarryOverDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = Repository.get(context)
    private val prefs = Preferences(context)

    @Test fun removingUndecryptableSourceDeletesItsItems() = runBlocking {
        val id = "carry-invalid-${System.nanoTime()}"
        val items = listOf(
            Item("$id-live", id, "Channel", "", "LIVE", "", "", "channel", 1, ""),
            Item("$id-movie", id, "Film", "", "MOVIE", "", "", "film", 1, ""),
        )
        try {
            // Like UI fixtures, this endpoint bypasses Vault and is shorter than its IV.
            repo.library.save(Source(id, id, "", false, 0, items.size))
            repo.library.save(items)
            assertNotNull(repo.library.source(id))
            items.forEach { assertNotNull(repo.library.item(it.id)) }

            repo.remove(id)

            assertNull(repo.library.source(id))
            items.forEach { assertNull(repo.library.item(it.id)) }
        } finally { repo.library.deleteSource(id) }
    }

    private fun published(id: String): WatchNextProgram? {
        context.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("package_name")) == context.packageName) {
                val program = WatchNextProgram.fromCursor(cursor)
                if (program.contentId == id) return program
            }
        }
        return null
    }

    private fun provider(failEpisodes: Boolean = false, extension: String = "mp4") = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val year = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }.get(Calendar.YEAR)
            val body = when (request.requestUrl?.queryParameter("action").orEmpty()) {
                "" -> """{"user_info":{"auth":1}}"""
                "get_live_streams" -> """[{"stream_id":"10","name":"Carry Channel"}]"""
                "get_vod_streams" -> """[{"stream_id":"20","name":"Carry Film","year":"$year"}]"""
                "get_series" -> """[{"series_id":"30","name":"Carry Show"}]"""
                "get_series_info" -> {
                    if (failEpisodes) return MockResponse().setResponseCode(503)
                    """{"episodes":{"1":[{"id":"40","episode_num":2,"season":1,"title":"Episode","container_extension":"$extension"}]}}"""
                }
                else -> "[]"
            }
            return MockResponse().setBody(body)
        }
    }

    @Test fun changedXtreamHostAndCredentialsKeepAllStateIncludingExpandedEpisodes() = runBlocking {
        MockWebServer().use { before -> MockWebServer().use { after ->
            before.dispatcher = provider()
            after.dispatcher = provider(extension = "mkv")
            before.start()
            after.start()
            val name = "Test · Carry ${System.currentTimeMillis()}"
            repo.add(name, SourceSpec(before.url("/").toString(), true, "old", "password"))
            val source = repo.library.snapshot().single { it.name == name }
            val recentBefore = prefs.recentLive.first()
            val watchNextBefore = prefs.text("watch_next", "Açık").first()
            val watchState = context.getSharedPreferences("watch_next_state", android.content.Context.MODE_PRIVATE)
            val excludedBefore = watchState.getStringSet("excluded", emptySet()).orEmpty().toSet()
            try {
                suspend fun item(kind: String) = repo.library.browse(source.id, kind, "", "", "", 20).first().single()
                val film = item("MOVIE")
                val show = item("SERIES")
                val channel = item("LIVE")
                val episode = repo.expand(show).single()
                val now = System.currentTimeMillis()
                repo.updateMark(film.id) { it.copy(favorite = true, listed = true, position = 12345, duration = 60000, updated = now) }
                repo.updateMark(episode.id) { it.copy(listed = true, position = 5432, duration = 60000, updated = now) }
                repo.library.collection(Member(film.id, "Carry Collection"))
                repo.guide.put(Feed("carry-feed-${source.id}", source.id, "unused"))
                repo.guide.map(Mapping(channel.id, "carry-feed-${source.id}", "channel"))
                prefs.recordLive(channel.id)
                repo.chooseVersion(film, film)
                prefs.set("audio_${repo.preferenceKey(episode)}", "en")
                prefs.set("text_${repo.preferenceKey(film)}", "off")
                watchState.edit().putStringSet("excluded", excludedBefore + show.id).commit()
                prefs.set("watch_next", "Açık")
                val publisher = WatchNext.get(context)
                publisher.sync()
                val oldProgram = checkNotNull(published(film.id))

                // This uses the same edit/import path exposed by Settings. The provider JSON is
                // unchanged except the episode extension: the source locator fingerprint must
                // still force a catalog rebuild when host and credentials change together.
                repo.editSource(source.id, source.name, SourceSpec(after.url("/").toString(), true, "new", "secret"))
                val movedFilm = item("MOVIE")
                val movedShow = item("SERIES")
                val movedChannel = item("LIVE")
                val movedEpisode = repo.episodeSnapshot(movedShow.id).single()
                assertTrue(repo.address(movedEpisode).endsWith("/series/new/secret/40.mkv"))
                assertNotEquals(film.id, movedFilm.id)
                assertNotEquals(episode.id, movedEpisode.id)
                assertEquals(12345L, repo.library.mark(movedFilm.id)?.position)
                assertEquals(60000L, repo.library.mark(movedFilm.id)?.duration)
                assertEquals(true, repo.library.mark(movedFilm.id)?.listed)
                assertEquals(true, repo.library.mark(movedFilm.id)?.favorite)
                assertEquals(now, repo.library.mark(movedFilm.id)?.updated)
                assertEquals(5432L, repo.library.mark(movedEpisode.id)?.position)
                assertEquals(movedFilm.id, repo.library.members(source.id, "Carry Collection").first().single().id)
                assertEquals(movedChannel.id, repo.guide.backupMappings().single { it.feed == "carry-feed-${source.id}" }.item)
                assertEquals(movedChannel.id, prefs.recentLive.first().first())
                assertEquals(movedFilm.id, repo.selectedVersion(movedFilm).id)
                assertEquals("id:${movedFilm.id}", prefs.text("version_${movedFilm.preferenceIdentity()}").first())
                assertEquals("en", prefs.playbackChoice("audio", repo.preferenceKey(movedEpisode)))
                assertEquals("off", prefs.playbackChoice("text", repo.preferenceKey(movedFilm)))
                assertEquals(movedFilm.id, repo.library.redirectedId(film.id))
                assertNull(repo.library.item(film.id))
                assertNull(repo.library.mark(film.id))
                publisher.sync()
                val newProgram = checkNotNull(published(movedFilm.id))
                assertEquals(oldProgram.id, newProgram.id)
                assertEquals(12345, newProgram.lastPlaybackPositionMillis)
                assertNull(published(film.id))
                assertTrue(watchState.getStringSet("excluded", emptySet()).orEmpty().contains(movedShow.id))
                // Repeated refresh must not lose an already migrated mark or re-create aliases.
                repo.refresh(source.id)
                assertEquals(12345L, repo.library.mark(movedFilm.id)?.position)
            } finally {
                repo.remove(source.id)
                prefs.set("recent_live", recentBefore.joinToString("\n"))
                prefs.set("watch_next", watchNextBefore)
                watchState.edit().putStringSet("excluded", excludedBefore).commit()
                WatchNext.get(context).sync()
            }
        } }
    }

    @Test fun changedPlaylistStreamHostCarriesStateDuringOrdinaryRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            fun playlist(host: String) = "#EXTM3U\n#EXTINF:-1 tvg-id=\"carry\" tvg-name=\"Carry\" group-title=\"Movies\",Carry Film\nhttps://$host/movie.mp4"
            server.enqueue(MockResponse().setBody(playlist("old.invalid")))
            val name = "Test · Carry M3U ${System.currentTimeMillis()}"
            repo.add(name, SourceSpec(server.url("/list").toString(), true))
            val source = repo.library.snapshot().single { it.name == name }
            try {
                val old = repo.library.browse(source.id, "MOVIE", "", "", "", 20).first().single()
                repo.updateMark(old.id) { it.copy(position = 123, listed = true, updated = System.currentTimeMillis()) }
                server.enqueue(MockResponse().setBody(playlist("new.invalid")))
                repo.refresh(source.id)
                val moved = repo.library.browse(source.id, "MOVIE", "", "", "", 20).first().single()
                assertNotEquals(old.id, moved.id)
                assertEquals(123L, repo.library.mark(moved.id)?.position)
                assertEquals(true, repo.library.mark(moved.id)?.listed)
                assertNull(repo.library.item(old.id))
            } finally { repo.remove(source.id) }
        }
    }
}
