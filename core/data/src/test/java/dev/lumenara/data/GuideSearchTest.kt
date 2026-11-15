package dev.lumenara.data

import dev.lumenara.catalog.searchable
import org.junit.Assert.*
import org.junit.Test

class GuideSearchTest {
    private val channels = mutableListOf<GuideChannel>()
    private val names = mutableListOf<GuideName>()
    private val mappings = mutableListOf<Mapping>()
    private val programmes = mutableListOf<Slot>()
    private fun save(rows: List<GuideChannel>) { channels.addAll(rows) }
    private fun put(rows: List<GuideName>) { names.addAll(rows) }
    private fun map(mapping: Mapping) { mappings.removeAll { it.item == mapping.item }; mappings.add(mapping) }
    private fun slots(rows: List<Slot>) { programmes.addAll(rows) }
    private fun resolver() = GuideResolver(channels, mappings, names,
        programmes.map { ProgrammeChannel(it.feed, it.channel) }.toSet())
    private fun searchPrograms(query: String, from: Long, now: Long) =
        resolver().searchPrograms(programmes, query, from, now)
    private fun nowAiring(now: Long, query: String = "") = resolver().nowAiring(programmes, now, query)
    private val now = 10 * 86_400_000L
    private val from get() = now - 7 * 86_400_000L
    private fun channel(id: String, title: String = id, tvg: String = id) = GuideChannel(id, title, tvg)
    private fun slot(channel: String, start: Long = now - 1000, end: Long = now + 1000,
                     title: String = "Özel program", feed: String = "f") =
        Slot(feed, channel, start, end, title, "Açıklama", searchable(title))

    @Test fun resolvesMappingThenTvgThenUniqueSearchableTitle() {
        save(listOf(channel("mapped", "Mapped", "direct"), channel("tvg", "TVG", "direct"),
            channel("name", "İĞDİR ÇÖŞÜ É's", "missing"), channel("ambiguous", "Ortak", "")))
        put(listOf(GuideName("f", "named", searchable("İĞDİR ÇÖŞÜ É's")),
            GuideName("f", "wrong", searchable("TVG")), GuideName("f", "a", "ortak"), GuideName("f", "b", "ortak")))
        map(Mapping("mapped", "f2", "manual"))
        slots(listOf(slot("direct"), slot("manual", feed = "f2"), slot("named"),
            slot("wrong"), slot("a"), slot("b")))
        val hits = searchPrograms("ozel", from, now)
        assertEquals(setOf("mapped", "tvg", "name"), hits.map { it.itemId }.toSet())
        assertEquals("manual", hits.single { it.itemId == "mapped" }.slot.channel)
        assertEquals("f2", hits.single { it.itemId == "mapped" }.slot.feed)
        assertEquals("direct", hits.single { it.itemId == "tvg" }.slot.channel)
        assertEquals(setOf("mapped", "tvg", "name"), nowAiring(now).map { it.itemId }.toSet())
        // A stale explicit mapping must not leak automatic results from another feed.
        map(Mapping("mapped", "f2", "absent"))
        assertFalse(searchPrograms("", from, now).any { it.itemId == "mapped" })
    }

    @Test fun sortsCurrentFuturePastFiltersAndLimits() {
        save(listOf(channel("one"), channel("two"), channel("none")))
        slots(listOf(slot("one", now - 100, now + 100, "Haber current"),
            slot("two", now - 200, now + 50, "Haber sooner"),
            slot("one", now + 100, now + 200, "Haber next"),
            slot("one", now + 300, now + 400, "Haber later"),
            slot("one", now - 200, now, "Haber ended"),
            slot("one", now - 300, now - 200, "Haber past"),
            slot("one", from - 100, from, "Haber expired"),
            slot("one", now + 500, now + 600, "Other")))
        val hits = searchPrograms("haber", from, now)
        assertEquals(listOf("Haber sooner", "Haber current", "Haber next", "Haber later", "Haber ended", "Haber past"), hits.map { it.slot.title })
        assertEquals(listOf("two", "one"), nowAiring(now).map { it.itemId })
        assertEquals(listOf("one"), nowAiring(now, "one").map { it.itemId })
        assertTrue(searchPrograms("absent", from, now).isEmpty())
        assertTrue(nowAiring(now, "absent").isEmpty())
        slots((1..80).map { slot("one", now + it * 1000, now + it * 1000 + 500, "Haber $it") })
        assertEquals(60, searchPrograms("haber", from, now).size)
    }

    @Test fun airingIncludesStartBoundaryAndDeduplicatesFeeds() {
        save(listOf(channel("one"), channel("ended")))
        slots(listOf(slot("one", now, now + 100, "First"),
            slot("one", now, now + 50, "Soonest", feed = "f2"),
            slot("ended", now - 100, now, "Ended")))
        val hits = nowAiring(now)
        assertEquals(listOf(AiringProgram("one", now + 50, "Soonest")), hits)
    }

    @Test fun tvgPresenceOutsideCandidatesStillSuppressesNameFallback() {
        val item = channel("item", "Named", "direct")
        val named = slot("named")
        val resolver = GuideResolver(listOf(item), emptyList(), listOf(GuideName("f", "named", "named")),
            setOf(ProgrammeChannel("f", "direct"), ProgrammeChannel("f", "named")))
        assertTrue(resolver.itemIds(named).isEmpty())
        assertEquals(listOf("item"), resolver.itemIds(slot("direct")))
    }

    @Test fun ambiguousNamesAreIgnoredPerFeedAndDuplicateNamesRemainUnique() {
        save(listOf(channel("item", "Ortak", "")))
        put(listOf(GuideName("f", "a", "ortak"), GuideName("f", "b", "ortak"),
            GuideName("f2", "a", "ortak"), GuideName("f2", "a", "ortak")))
        slots(listOf(slot("a"), slot("b"), slot("a", feed = "f2")))
        assertEquals(listOf("f2"), searchPrograms("", from, now).map { it.slot.feed })
    }

    @Test fun airingNormalizesQueryAndMatchesItemSearchOrTitle() {
        save(listOf(channel("one", "İĞDİR ÇÖŞÜ É's").copy(search = "sports")))
        slots(listOf(slot("one")))
        assertEquals(listOf("one"), nowAiring(now, "İĞDİR").map { it.itemId })
        assertEquals(listOf("one"), nowAiring(now, "SPORTS").map { it.itemId })
        assertTrue(nowAiring(now, "Özel program").isEmpty())
        assertEquals(1, searchPrograms("ÖZEL", from, now).size)
    }

    @Test fun airingBreaksEndTiesByItemIdAndAllowsSeveralItemsPerSlot() {
        save(listOf(channel("b", tvg = "shared"), channel("a", tvg = "shared")))
        slots(listOf(slot("shared"), slot("shared", feed = "f2")))
        assertEquals(listOf("a", "b"), nowAiring(now).map { it.itemId })
        assertEquals(4, searchPrograms("", from, now).size)
    }

    @Test fun allSourcesDoNotResolveAutomaticMatchesAcrossSources() {
        val items = listOf(channel("a", tvg = "same").copy(source = "s1"),
            channel("b", "Named", "missing").copy(source = "s2"))
        val resolver = GuideResolver(items, emptyList(), listOf(GuideName("f1", "same", "named")),
            setOf(ProgrammeChannel("f1", "same", "s1"), ProgrammeChannel("f2", "other", "s2")))
        assertEquals(listOf("a"), resolver.itemIds(slot("same", feed = "f1")))
        assertTrue(resolver.itemIds(slot("same", feed = "f2")).isEmpty())
    }

    @Test fun explicitMappingCannotMatchAnotherSourcesFeed() {
        val resolver = GuideResolver(listOf(channel("a").copy(source = "s1")),
            listOf(Mapping("a", "f2", "a")), emptyList(),
            setOf(ProgrammeChannel("f1", "a", "s1"), ProgrammeChannel("f2", "a", "s2")))
        assertTrue(resolver.itemIds(slot("a", feed = "f1")).isEmpty())
        assertTrue(resolver.itemIds(slot("a", feed = "f2")).isEmpty())
    }

    @Test fun hiddenChannelsAreExcludedBeforeTheSixtyHitLimit() {
        save((0 until 65).map { channel("%02d".format(it), tvg = "shared").copy(
            category = if (it < 60) "Adult" else "News") })
        slots(listOf(slot("shared")))
        assertEquals(5, resolver().searchPrograms(programmes, "", from, now, listOf("Adult")).size)
    }

    @Test fun cardTargetsUseTheSameMappingPriorityAndDoNotCrossSources() {
        val resolver = GuideResolver(listOf(channel("manual").copy(source = "s1"),
            channel("direct", "Named", "tvg").copy(source = "s1"),
            channel("named", "Named", "absent").copy(source = "s1")),
            listOf(Mapping("manual", "f1", "chosen")), listOf(GuideName("f1", "name", "named")),
            setOf(ProgrammeChannel("f1", "tvg", "s1"), ProgrammeChannel("f2", "tvg", "s2")))
        assertEquals(listOf(GuideTarget("f1", "chosen")), resolver.targets("manual"))
        assertEquals(listOf(GuideTarget("f1", "tvg")), resolver.targets("direct"))
        assertEquals(listOf(GuideTarget("f1", "name")), resolver.targets("named"))
    }

}
