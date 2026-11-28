package dev.lumenara.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Wp03EpisodeCarryTest {
    private val parent = CarryRow("show", "source", "Show", "Shows", "SERIES", "", "", "", "10", "", "")
    private fun episode(id: String, provider: String = "10:100", number: String = "S1E1", owner: String = parent.id) =
        parent.copy(id = id, title = "Show · $number · Title", kind = "EPISODE", parent = owner, providerId = provider)

    @Test fun providerIdentityCarriesChangedUrlExtensionAndPassword() {
        val before = episode("old-url-hash")
        val after = before.copy(id = "new-url-hash", title = "Renamed · S2E2")
        assertEquals(mapOf(before.id to after.id), wp03EpisodeMatches(parent, listOf(before), listOf(after)).ids)
    }

    @Test fun sameSeasonEpisodeFallsBackWithinTheKnownParentOnly() {
        val old = listOf(episode("s1", "10:100"), episode("s2", "10:101", "S2E1"))
        val incoming = listOf(episode("new-s2", "10:201", "S2E1"), episode("new-s1", "10:200"),
            episode("sibling", "11:100", owner = "other-show"), episode("foreign").copy(source = "other-source"))
        assertEquals(mapOf("s1" to "new-s1", "s2" to "new-s2"), wp03EpisodeMatches(parent, old, incoming).ids)
    }

    @Test fun duplicateProviderOrSeasonEpisodeNeverChoosesAnArbitraryRendition() {
        val old = episode("old")
        assertTrue(wp03EpisodeMatches(parent, listOf(old), listOf(episode("a"), episode("b"))).ids.isEmpty())
        assertTrue(wp03EpisodeMatches(parent, listOf(old),
            listOf(episode("a", "10:200"), episode("b", "10:201"))).ids.isEmpty())
        val peers = listOf(episode("a", "10:200"), episode("b", "10:201"))
        assertTrue(wp03EpisodeMatches(parent, peers, listOf(episode("new", "10:300"))).ids.isEmpty())
    }

    @Test fun siblingFlagsMergeAndNewestProgressWinsInBothDirections() {
        val target = episode("target")
        val a = episode("a", owner = "dubbed")
        val b = episode("b", owner = "subbed")
        val old = Mark(a.id, favorite = true, position = 100, duration = 1_000, updated = 10)
        val newest = Mark(b.id, listed = true, position = 800, duration = 2_000, updated = 30)
        val shared = wp03SiblingMarks(listOf(target), listOf(a, b), mapOf(a.id to old, b.id to newest)).getValue(target.id)
        assertEquals(newest.copy(id = target.id, favorite = true), shared)
        val current = Mark(target.id, position = 500, duration = 3_000, updated = 40)
        assertEquals(current.copy(favorite = true, listed = true), mergeCarryMark(shared, current, target.id))
        assertEquals(shared, mergeCarryMark(shared, current.copy(updated = 20), target.id))
    }

    @Test fun ambiguousSiblingOrTargetNumbersDoNotShareMarks() {
        val a = episode("a", owner = "dubbed")
        val b = episode("b", owner = "dubbed")
        val states = mapOf(a.id to Mark(a.id, position = 5, updated = 1))
        assertTrue(wp03SiblingMarks(listOf(episode("target")), listOf(a, b), states).isEmpty())
        assertTrue(wp03SiblingMarks(listOf(episode("target"), episode("duplicate")), listOf(a), states).isEmpty())
        assertTrue(wp03SiblingMarks(listOf(episode("target").copy(title = "No number")), listOf(a), states).isEmpty())
    }

    @Test fun unresolvedHistoryRetainsUnmarkedAmbiguityPeersAndParent() {
        val a = episode("a")
        val b = episode("b", "10:101")
        val snapshot = orphanHistory(parent.source, "provider", System.currentTimeMillis(), listOf(parent, a, b),
            listOf(Mark(a.id, favorite = true)), emptyList(), emptySet())
        assertEquals(setOf(parent.id, a.id, b.id), snapshot.rows.map { it.id }.toSet())
        assertTrue(wp03EpisodeMatches(parent, snapshot.rows, listOf(episode("new", "10:200"))).ids.isEmpty())
        assertEquals(snapshot, decodeOrphanHistory(encodeOrphanHistory(listOf(snapshot))).single())
    }

    @Test fun existingRecoveryPayloadWithoutUrlHashOrOptionalReferencesStillReads() {
        val old = """[{"source":"source","provider":"provider","deleted":1,"rows":[["ep","source","Show S1E1","Season 1","EPISODE","","","show","10:100","","",""]],"marks":[["ep",true,true,123,1000,10]],"members":[["ep","Collection"]]}]"""
        val decoded = decodeOrphanHistory(old).single()
        assertEquals("", decoded.rows.single().urlHash)
        assertEquals(123L, decoded.marks.single().position)
        assertEquals(decoded, decodeOrphanHistory(encodeOrphanHistory(listOf(decoded))).single())
    }

    @Test fun missingNullOrMalformedEpisodesFailWhileExplicitEmptyCatalogSucceeds() {
        for (body in listOf("{}", """{"episodes":null}""", """{"episodes":"error"}""",
            """{"episodes":[{"id":"1"}]}""", """{"episodes":{"1":null}}""",
            """{"episodes":{"1":{}}}""")) {
            try {
                wp03EpisodeSeasons(JSONObject(body))
                fail("Expected incomplete episode catalog to fail")
            } catch (_: SourceFailure) { }
        }
        assertEquals(0, wp03EpisodeSeasons(JSONObject("""{"episodes":{}}""")).length())
        assertEquals(0, wp03EpisodeSeasons(JSONObject("""{"episodes":[]}""")).length())
    }

    @Test fun skippedMalformedEntryRetainsUnresolvedHistoryUntilAValidRetry() = MockWebServer().use { server ->
        val xtream = Xtream(SourceSpec(server.url("/").toString(), true, "u", "p"))
        fun parse(body: String): List<CarryRow> {
            server.enqueue(MockResponse().setBody(body))
            return xtream.episodes(server.url("/player_api.php?series_id=10").toString(), "Show")
                .map { parent.copy(id = it.url, title = it.title, category = it.group,
                    kind = "EPISODE", parent = parent.id, providerId = it.providerId) }
        }
        val old = parse("""{"episodes":{"1":[{"id":"100","episode_num":1},{"id":"101","episode_num":2},{"id":"102","episode_num":3}]}}""")
        val skipped = old[0]
        val mark = Mark(skipped.id, favorite = true, listed = true, position = 123, duration = 1_000, updated = 10)
        val member = Member(skipped.id, "Saved")
        val mixed = """{"episodes":{"1":[null,"bad",{}, {"id":""},{"episode_num":1,"title":"Missing id"},{"id":"102","episode_num":3,"container_extension":"mkv"}]}}"""
        var previous = old
        repeat(2) { refresh ->
            val incoming = parse(mixed)
            assertEquals(listOf("10:102"), incoming.map { it.providerId })
            val matches = wp03EpisodeMatches(parent, previous, incoming)
            val previousValidId = previous.single { it.providerId == "10:102" }.id
            val incomingId = incoming.single().id
            // First refresh changes mp4 -> mkv. The repeated response keeps the same ID:
            // matchCarryOver reserves unchanged IDs but returns only IDs that need moving.
            if (refresh == 0) {
                assertNotEquals(previousValidId, incomingId)
                assertEquals(mapOf(previousValidId to incomingId), matches.ids)
            } else {
                assertEquals(previousValidId, incomingId)
                assertEquals(emptyMap<String, String>(), matches.ids)
            }
            assertFalse(matches.ids.containsKey(skipped.id))
            // Repository uses the DAO's protected IDs as references before moving state/pruning.
            val pending = orphanHistory(parent.source, "", 1, previous + parent,
                emptyList(), emptyList(), setOf(mark.id, member.id))
            val retained = wp03UnresolvedEpisodeIds(pending, incoming.map { it.id }.toSet(), matches.ids.keys)
            assertEquals(listOf(skipped.id), retained)
            // The saved mark and collection remain recoverable with the retained row.
            val history = orphanHistory(parent.source, "", 1, pending.rows,
                listOf(mark), listOf(member), emptySet())
            val decoded = decodeOrphanHistory(encodeOrphanHistory(listOf(history))).single()
            assertEquals(listOf(mark), decoded.marks)
            assertEquals(listOf(member), decoded.members)
            previous = incoming + previous.filter { it.id in retained }
        }
        val retry = parse("""{"episodes":{"1":[{"id":"100","episode_num":1,"container_extension":"mkv"}]}}""")
        assertEquals(retry.single().id, wp03EpisodeMatches(parent, previous, retry).ids[skipped.id])
    }

    @Test fun thousandChangedLocatorsHaveNoLostOrCrossSeasonMatches() {
        val old = (0 until 1_000).map { episode("old-$it", "10:$it", "S${it / 100 + 1}E${it % 100 + 1}") }
        val incoming = old.mapIndexed { index, row -> row.copy(id = "new-$index",
            providerId = if (index % 2 == 0) row.providerId else "10:${index + 10_000}") }.reversed()
        val match = wp03EpisodeMatches(parent, old, incoming)
        assertEquals((0 until 1_000).associate { "old-$it" to "new-$it" }, match.ids)
        assertEquals(0, match.ambiguous)
        assertEquals(0, match.unmatched)
    }
}
