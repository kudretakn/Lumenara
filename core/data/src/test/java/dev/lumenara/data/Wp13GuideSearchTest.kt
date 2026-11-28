package dev.lumenara.data

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import dev.lumenara.catalog.searchable
import org.junit.Assert.*
import org.junit.Test

class Wp13GuideSearchTest {
    @Test fun searchBindsEligibleTargetsAndNormalizedQueryBelowAndroidNineLimit() {
        val targets = (0 until 300).map { Wp13GuideSearchTarget("item$it", "feed", "channel$it") }
        val query = wp13GuideSearchQuery(targets, "İÇ ' Haber", 10L, 20L)
        assertEquals(903, query.argCount)
        assertEquals(listOf("item0", "feed", "channel0"), arguments(query).take(3))
        assertEquals(listOf("ic ' haber", 10L, 20L), arguments(query).takeLast(3))
        assertFalse(query.sql.contains("Haber"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedTargetBatchMustBeChunked() {
        wp13GuideSearchQuery((0..300).map { Wp13GuideSearchTarget("i$it", "f", "c") }, "", 0, 1)
    }

    @Test fun eligibilityKeepsMappingPriorityVisibilityAmbiguityAndSourceOwnership() {
        val channels = listOf(
            GuideChannel("manual", "Named", "direct", source = "s"),
            GuideChannel("direct", "Named", "direct", source = "s"),
            GuideChannel("name", "İsim", "absent", source = "s"),
            GuideChannel("ambiguous", "Shared", "", source = "s"),
            GuideChannel("stale", "Named", "direct", source = "s"),
            GuideChannel("cross", "Named", "direct", source = "s"),
            GuideChannel("hidden", "Named", "direct", source = "s", category = "Adult"),
        )
        val resolver = GuideResolver(channels, listOf(Mapping("manual", "f", "chosen"),
            Mapping("stale", "f", "missing"), Mapping("cross", "foreign", "direct")),
            listOf(GuideName("f", "named", "named"), GuideName("f", "by-name", searchable("İsim")),
                GuideName("f", "a", "shared"), GuideName("f", "b", "shared")),
            setOf(ProgrammeChannel("f", "direct", "s"), ProgrammeChannel("f", "chosen", "s"),
                ProgrammeChannel("foreign", "direct", "other")))
        assertEquals(setOf(Wp13GuideSearchTarget("manual", "f", "chosen"),
            Wp13GuideSearchTarget("direct", "f", "direct"), Wp13GuideSearchTarget("name", "f", "by-name"),
            Wp13GuideSearchTarget("stale", "f", "missing")), resolver.searchTargets(listOf("Adult")).toSet())
        assertTrue(resolver.searchTargets(emptyList()).any { it.itemId == "hidden" })
    }

    @Test fun chunkTopSixtyMergeEqualsUnboundedReferenceIncludingAllTiesAndTimeRanks() {
        val now = System.currentTimeMillis()
        val channels = (0 until 905).map { GuideChannel("i%04d".format(it), "Channel", "c$it", source = "s") }
        val slots = channels.flatMapIndexed { index, channel ->
            listOf(Slot("f", channel.tvgId, now - 100, now + 100, "Current", "", "current"),
                Slot("f", channel.tvgId, now + 100, now + 200, "Next", "", "next"),
                Slot("f", channel.tvgId, now - 300 - index, now - 200, "Past", "", "past"))
        }
        val resolver = GuideResolver(channels, emptyList(), emptyList(),
            channels.map { ProgrammeChannel("f", it.tvgId, "s") }.toSet())
        for (query in listOf("", "current", "next", "past", "absent")) {
            val from = now - 1000
            val merged = resolver.searchTargets(emptyList()).reversed().chunked(WP13_GUIDE_SEARCH_TARGETS)
                .flatMap { targets ->
                    slots.asSequence().filter { it.end > from && query in it.search }.flatMap { slot ->
                        targets.asSequence().filter { it.feed == slot.feed && it.channel == slot.channel }
                            .map { ProgramHit(slot, it.itemId) }
                    }.sortedWith(wp13ProgramHitOrder(now)).take(60).toList()
                }.sortedWith(wp13ProgramHitOrder(now)).take(60)
            assertEquals(query, resolver.searchPrograms(slots, query, from, now), merged)
        }
    }

    private fun arguments(query: SupportSQLiteQuery): List<Any?> {
        val values = MutableList<Any?>(query.argCount) { null }
        query.bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { values[index - 1] = null }
            override fun bindLong(index: Int, value: Long) { values[index - 1] = value }
            override fun bindDouble(index: Int, value: Double) { values[index - 1] = value }
            override fun bindString(index: Int, value: String) { values[index - 1] = value }
            override fun bindBlob(index: Int, value: ByteArray) { values[index - 1] = value }
            override fun clearBindings() = Unit
            override fun close() = Unit
        })
        return values
    }
}
