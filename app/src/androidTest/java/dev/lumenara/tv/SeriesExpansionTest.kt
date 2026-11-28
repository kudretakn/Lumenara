package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SeriesExpansionTest {
    @Test fun reopeningAnXtreamSeriesUsesCachedEpisodesAndPreservesMarks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = Repository.get(context)
        val name = "Test · P4 expansion ${System.nanoTime()}"
        val requests = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when (request.requestUrl?.queryParameter("action")) {
                        null -> """{"user_info":{"auth":1}}"""
                        "get_series_categories" -> """[{"category_id":"3","category_name":"Diziler"}]"""
                        "get_series" -> """[{"series_id":10,"name":"P4 Dizi Dublaj","category_id":"3"},{"series_id":11,"name":"P4 Dizi Altyazılı","category_id":"3"}]"""
                        "get_series_info" -> {
                            requests.incrementAndGet()
                            val id = if (request.requestUrl?.queryParameter("series_id") == "10") 100 else 110
                            """{"episodes":{"1":[{"id":"${id + 1}","episode_num":1,"title":"İlk","container_extension":"mp4","info":{"duration_secs":100}},{"id":"${id + 2}","episode_num":2,"title":"Son","container_extension":"mp4","info":{"duration_secs":200}}]}}"""
                        }
                        else -> "[]"
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            try {
                repo.add(name, SourceSpec(server.url("/").toString(), true, "u", "p"))
                val source = repo.library.snapshot().single { it.name == name }
                val series = repo.library.browse(source.id, "SERIES", "", "", "", 10).first().single()
                val episodes = repo.expand(series).seriesOrder()
                assertEquals(2, episodes.size)
                assertEquals(100_000L, repo.library.mark(episodes.first().id)?.duration)
                val progress = Mark(episodes.first().id, favorite = true, position = 500, duration = 100_000, updated = 5)
                repo.library.save(progress)
                coroutineScope {
                    val a = async { repo.expand(series).seriesOrder() }
                    val b = async { repo.expand(series).seriesOrder() }
                    assertEquals(episodes.map { it.id }, a.await().map { it.id })
                    assertEquals(episodes.map { it.id }, b.await().map { it.id })
                }
                assertEquals(1, requests.get())
                assertEquals(progress, repo.library.mark(episodes.first().id))
                assertTrue(Preferences(context).text("series_expanded_${series.id}").first().toLong() > 0)
                // Batched sibling marks must still transfer progress by season/episode number.
                val sibling = repo.library.versionRows(series.versionKey).single { it.id != series.id }
                val alternate = repo.expand(sibling).seriesOrder()
                assertEquals(progress.copy(id = alternate.first().id), repo.library.mark(alternate.first().id))
                repo.expand(sibling)
                assertEquals(2, requests.get())
            } finally {
                repo.library.snapshot().filter { it.name == name }.forEach { repo.remove(it.id) }
            }
        }
    }

    private fun episodeJson(id: Int, number: Int = 1, season: Int = 1, extension: String = "mp4") =
        """{"id":"$id","episode_num":$number,"season":$season,"title":"Episode","container_extension":"$extension","info":{"duration_secs":100}}"""

    private fun episodeBody(rows: List<String>) = """{"episodes":{"1":[${rows.joinToString(",")}]}}"""

    private suspend fun withRefreshFixture(test: suspend (Repository, Item, Item, AtomicReference<String>, AtomicInteger) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = Repository.get(context)
        val body = AtomicReference(episodeBody(listOf(episodeJson(101), episodeJson(102, season = 2))))
        val requests = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = when (request.requestUrl?.queryParameter("action")) {
                        null -> """{"user_info":{"auth":1}}"""
                        "get_series" -> """[{"series_id":10,"name":"WP03 Show Dublaj"},{"series_id":11,"name":"WP03 Show Altyazılı"}]"""
                        "get_series_info" -> {
                            requests.incrementAndGet()
                            if (request.requestUrl?.queryParameter("series_id") == "10") body.get()
                            else episodeBody(listOf(episodeJson(201), episodeJson(202, season = 2)))
                        }
                        else -> "[]"
                    }
                    return MockResponse().setBody(response)
                }
            }
            server.start()
            val name = "Test · WP03 expansion ${System.nanoTime()}"
            repo.add(name, SourceSpec(server.url("/").toString(), true, "u", "p"))
            val source = repo.library.snapshot().single { it.name == name }
            try {
                val series = repo.library.carryRows(source.id).filter { it.kind == "SERIES" }
                    .map { checkNotNull(repo.library.item(it.id)) }
                test(repo, series.single { it.providerId == "10" }, series.single { it.providerId == "11" }, body, requests)
            } finally { repo.remove(source.id) }
        }
    }

    private suspend fun expireExpansion(repo: Repository, series: Item): Long {
        // Exercise the production six-hour boundary without sleeping or adding a production test API.
        val stamp = System.currentTimeMillis() - 7 * 60 * 60 * 1000L
        Preferences(InstrumentationRegistry.getInstrumentation().targetContext).set("series_expanded_${series.id}", stamp.toString())
        val cache = Repository::class.java.getDeclaredField("seriesExpansionCache").apply { isAccessible = true }.get(repo)
        val stamps = cache.javaClass.getDeclaredField("stamps").apply { isAccessible = true }.get(cache)
        (stamps as MutableMap<*, *>).remove(series.id)
        return stamp
    }

    @Test fun expiredRefreshCarriesChangedLocatorsMergesSiblingFlagsAndKeepsNewestProgress() = runBlocking {
        withRefreshFixture { repo, series, sibling, body, _ ->
            val before = repo.expand(series).seriesOrder()
            val alternate = repo.expand(sibling).seriesOrder()
            val older = Mark(before[0].id, favorite = true, position = 100, duration = 100_000, updated = 10)
            val newer = Mark(before[1].id, listed = true, position = 900, duration = 100_000, updated = 30)
            repo.library.save(older)
            repo.library.save(newer)
            repo.library.save(Mark(alternate[0].id, listed = true, position = 500, duration = 100_000, updated = 20))
            repo.library.save(Mark(alternate[1].id, favorite = true, position = 200, duration = 100_000, updated = 20))
            repo.library.collection(Member(before[0].id, "WP03 Collection"))
            val prefs = Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
            prefs.set("subtitle_delay_${series.source}|${before[0].id}", "750")
            // Same provider ID with changed extension, then changed ID matched only by S/E.
            body.set(episodeBody(listOf(episodeJson(101, extension = "mkv"), episodeJson(302, season = 2))))
            expireExpansion(repo, series)
            val after = repo.expand(series).seriesOrder()
            assertEquals(2, after.size)
            assertEquals(Mark(after[0].id, true, true, 500, 100_000, 20), repo.library.mark(after[0].id))
            assertEquals(newer.copy(id = after[1].id, favorite = true), repo.library.mark(after[1].id))
            assertEquals("WP03 Collection", repo.library.member(after[0].id)?.name)
            assertEquals("750", prefs.text("subtitle_delay_${series.source}|${after[0].id}").first())
            assertEquals("", prefs.text("subtitle_delay_${series.source}|${before[0].id}").first())
            before.zip(after).forEach { (old, new) ->
                assertNotEquals(old.id, new.id)
                assertNull(repo.library.item(old.id))
                assertNull(repo.library.mark(old.id))
                assertEquals(new.id, repo.library.redirectedId(old.id))
            }
        }
    }

    @Test fun incompleteEpisodeResponsesPreserveCatalogMarksAndRetryWithoutUpdatingCache() = runBlocking {
        withRefreshFixture { repo, series, _, body, requests ->
            val before = repo.expand(series).seriesOrder()
            val mark = Mark(before.first().id, favorite = true, listed = true, position = 100, duration = 100_000, updated = 10)
            repo.library.save(mark)
            val stamp = expireExpansion(repo, series)
            val prefs = Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
            for (response in listOf("{}", """{"episodes":null}""", """{"episodes":{"1":null}}""",
                """{"episodes":"error"}""")) {
                body.set(response)
                try { repo.expand(series); fail("Expected incomplete response to fail") }
                catch (_: SourceFailure) { }
                assertEquals(before, repo.library.episodeSnapshot(series.id).seriesOrder())
                assertEquals(mark, repo.library.mark(mark.id))
                assertEquals(stamp.toString(), prefs.text("series_expanded_${series.id}").first())
            }
            body.set(episodeBody(listOf(episodeJson(101, extension = "mkv"), episodeJson(102, season = 2))))
            val after = repo.expand(series).seriesOrder()
            assertEquals(mark.copy(id = after.first().id), repo.library.mark(after.first().id))
            assertEquals(6, requests.get()) // Initial fetch, four failed attempts, successful retry.
            repo.expand(series)
            assertEquals(6, requests.get())
        }
    }

    @Test fun skippedMalformedEpisodeRetainsMarksAndCollectionWhileValidEntriesRefresh() = runBlocking {
        withRefreshFixture { repo, series, _, body, requests ->
            body.set(episodeBody(listOf(episodeJson(101), episodeJson(102, number = 2), episodeJson(103, number = 3))))
            val before = repo.expand(series).seriesOrder()
            val mark = Mark(before[0].id, favorite = true, listed = true, position = 123, duration = 100_000, updated = 10)
            val validMark = Mark(before[2].id, favorite = true, position = 456, duration = 100_000, updated = 20)
            repo.library.save(mark)
            repo.library.save(validMark)
            repo.library.collection(Member(mark.id, "WP03 Skipped"))
            body.set(episodeBody(listOf("null", "{}", """{"episode_num":1,"title":"Missing id"}""",
                episodeJson(103, number = 3, extension = "mkv"))))
            repeat(2) {
                expireExpansion(repo, series)
                val after = repo.expand(series)
                assertEquals(2, after.size)
                assertTrue(after.any { it.id == mark.id })
                assertEquals(mark, repo.library.mark(mark.id))
                assertEquals("WP03 Skipped", repo.library.member(mark.id)?.name)
                assertNull(repo.library.item(before[1].id))
                val valid = after.single { it.providerId == "10:103" }
                assertNotEquals(before[2].id, valid.id)
                assertEquals(validMark.copy(id = valid.id), repo.library.mark(valid.id))
                assertEquals(valid.id, repo.library.redirectedId(before[2].id))
                assertNull(repo.library.mark(before[2].id))
                assertNull(repo.library.item(before[2].id))
            }
            repo.expand(series)
            assertEquals(3, requests.get()) // Initial fetch and two successful mixed responses.
            body.set(episodeBody(listOf(episodeJson(101, extension = "mkv"), episodeJson(103, number = 3, extension = "mkv"))))
            expireExpansion(repo, series)
            val recovered = repo.expand(series).single { it.providerId == "10:101" }
            assertEquals(mark.copy(id = recovered.id), repo.library.mark(recovered.id))
            assertEquals("WP03 Skipped", repo.library.member(recovered.id)?.name)
            assertNull(repo.library.item(mark.id))
        }
    }

    @Test fun ambiguousRefreshRetainsHistoryAndUnmarkedPeersAcrossRetries() = runBlocking {
        withRefreshFixture { repo, series, _, body, _ ->
            body.set(episodeBody(listOf(episodeJson(101), episodeJson(102))))
            val before = repo.expand(series)
            val old = before.single { it.providerId == "10:101" }
            val peer = before.single { it.providerId == "10:102" }
            val mark = Mark(old.id, favorite = true, listed = true, position = 123, duration = 100_000, updated = 10)
            repo.library.save(mark)
            repo.library.collection(Member(old.id, "WP03 Unresolved"))
            body.set(episodeBody(listOf(episodeJson(301))))
            repeat(2) {
                expireExpansion(repo, series)
                val rows = repo.expand(series)
                assertEquals(3, rows.size)
                assertTrue(rows.map { it.id }.containsAll(listOf(old.id, peer.id)))
                assertEquals(1, rows.count { it.providerId == "10:301" })
                assertEquals(mark, repo.library.mark(old.id))
                assertNotNull(repo.library.item(peer.id))
                assertEquals("WP03 Unresolved", repo.library.member(old.id)?.name)
                assertNull(repo.library.mark(rows.single { it.providerId == "10:301" }.id)?.takeIf { it.updated > 0 })
                assertNull(repo.library.redirectedId(old.id))
            }
            // A later strong provider match can recover safely, without guessing the S/E peer.
            body.set(episodeBody(listOf(episodeJson(101, extension = "mkv"))))
            expireExpansion(repo, series)
            val recovered = repo.expand(series).single { it.providerId == "10:101" }
            assertNotEquals(old.id, recovered.id)
            assertEquals(mark.copy(id = recovered.id), repo.library.mark(recovered.id))
            assertNull(repo.library.item(old.id))
        }
    }

    @Test fun explicitEmptyCatalogPrunesUnmarkedEpisodesAndCachesSuccessWithoutLosingHistory() = runBlocking {
        withRefreshFixture { repo, series, _, body, requests ->
            val before = repo.expand(series).seriesOrder()
            val mark = Mark(before[0].id, listed = true, position = 123, duration = 100_000, updated = 10)
            repo.library.save(mark)
            body.set("""{"episodes":{}}""")
            expireExpansion(repo, series)
            assertEquals(listOf(before[0].id), repo.expand(series).map { it.id })
            assertEquals(mark, repo.library.mark(before[0].id))
            assertNull(repo.library.item(before[1].id))
            repo.expand(series)
            assertEquals(2, requests.get())
        }
    }

    @Test fun thousandEpisodeRefreshPreservesEveryMarkWithoutCrossSeasonMatches() = runBlocking {
        withRefreshFixture { repo, series, _, body, _ ->
            fun rows(changed: Boolean) = (0 until 1_000).map { index ->
                val id = if (changed && index % 2 != 0) index + 10_000 else index + 1_000
                episodeJson(id, index % 100 + 1, index / 100 + 1, if (changed) "mkv" else "mp4")
            }
            body.set(episodeBody(rows(false)))
            val before = repo.expand(series).seriesOrder()
            assertEquals(1_000, before.size)
            val marks = before.mapIndexed { index, row ->
                Mark(row.id, index % 2 == 0, index % 2 != 0, index.toLong() + 1, 100_000, index.toLong() + 1)
            }
            marks.forEach { repo.library.save(it) }
            body.set(episodeBody(rows(true).reversed()))
            expireExpansion(repo, series)
            val after = repo.expand(series).seriesOrder()
            assertEquals(1_000, after.size)
            val persisted = after.map { it.id }.chunked(900).flatMap { repo.library.marksByIds(it) }.associateBy { it.id }
            assertEquals(1_000, persisted.size)
            before.indices.forEach { index ->
                assertNotEquals(before[index].id, after[index].id)
                assertEquals(marks[index].copy(id = after[index].id), persisted[after[index].id])
                assertNull(repo.library.item(before[index].id))
                assertEquals(after[index].id, repo.library.redirectedId(before[index].id))
            }
        }
    }
}
