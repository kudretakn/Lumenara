package dev.lumenara.data

import java.util.Calendar
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class HistoryCarryOverTest {
    private class MemoryStore : DataStore<StoredPreferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (StoredPreferences) -> StoredPreferences): StoredPreferences =
            mutex.withLock { transform(data.value).toPreferences().also { data.value = it } }
    }
    private val year = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }.get(Calendar.YEAR).toString()
    private fun row(id: String, title: String, kind: String = "MOVIE", provider: String = "", date: String = year,
        tvg: String = "", name: String = "", group: String = "Films", parent: String = "") =
        CarryRow(id, "source", title, group, kind, tvg, date, parent, provider, name, group)

    @Test fun subtitleDelayPreferenceFollowsItemId() = runBlocking {
        val prefs = Preferences(MemoryStore(), { it }, { it })
        prefs.set("subtitle_delay_source|old-item", "750")
        prefs.carryItems(mapOf("old-item" to "new-item"), emptyMap())
        assertEquals("750", prefs.text("subtitle_delay_source|new-item").first())
        assertEquals("", prefs.text("subtitle_delay_source|old-item").first())
    }

    @Test fun xtreamIdsSurviveChangedHostCredentialsAndTitlesButRespectKind() {
        val old = listOf(row("a", "Before", provider = "42"), row("b", "TV", "LIVE", "42"))
        val new = listOf(row("c", "After", provider = "42"), row("d", "Television", "LIVE", "42"))
        assertEquals(mapOf("a" to "c", "b" to "d"), matchCarryOver(old, new).ids)
        assertEquals("42", legacyProviderId("https://new.invalid/movie/changed/credentials/42.mkv", "MOVIE"))
        assertEquals("7:42", legacyProviderId("https://new.invalid/series/u/p/42.mp4", "EPISODE", "7"))
    }

    @Test fun moviesFallBackToNormalizedTitleAndYear() {
        val old = row("a", "TR: Arrival ($year) 4K Dublaj")
        assertEquals(mapOf("a" to "b"), matchCarryOver(listOf(old), listOf(row("b", "Arrival", date = year))).ids)
        val different = (year.toInt() - 1).toString()
        assertTrue(matchCarryOver(listOf(old), listOf(row("b", "Arrival", date = different))).ids.isEmpty())
    }

    @Test fun ambiguousDuplicatesNeverFallThroughToWeakerKeys() {
        val old = listOf(row("a", "Film", provider = "1"), row("b", "Other", provider = "1"))
        val result = matchCarryOver(old, listOf(row("c", "Film", provider = "1")))
        assertTrue(result.ids.isEmpty())
        assertEquals(2, result.ambiguous)
        assertTrue(matchCarryOver(listOf(row("a", "Film")), listOf(row("b", "Film"), row("c", "Film"))).ids.isEmpty())
    }

    @Test fun episodesMatchSeriesAndSeasonEpisodeWithoutMatchingEpisodeTitles() {
        val old = listOf(row("a", "The Show S01E02 Pilot", "EPISODE"), row("b", "The Show S02E02 Next", "EPISODE"))
        val new = listOf(row("c", "the show S2E2 Renamed", "EPISODE"), row("d", "the show S1E2 Renamed", "EPISODE"))
        assertEquals(mapOf("a" to "d", "b" to "c"), matchCarryOver(old, new).ids)
        assertTrue(matchCarryOver(listOf(row("a", "Show", "EPISODE")), listOf(row("b", "Show", "EPISODE"))).ids.isEmpty())
    }

    @Test fun playlistIdsThenNamesRequireTheSameGroup() {
        val old = row("a", "Before", "LIVE", tvg = "channel", group = "Sports")
        assertEquals(mapOf("a" to "b"), matchCarryOver(listOf(old), listOf(old.copy(id = "b", title = "After"))).ids)
        assertTrue(matchCarryOver(listOf(old), listOf(old.copy(id = "b", title = "After", tvgGroup = "News"))).ids.isEmpty())
        val named = row("a", "Before", "LIVE", name = "Channel", group = "Sports")
        assertEquals(mapOf("a" to "b"), matchCarryOver(listOf(named), listOf(named.copy(id = "b", title = "After"))).ids)
    }

    @Test fun unchangedRowsReserveTheirIdentityAgainstDuplicates() {
        val old = listOf(row("a", "Film"), row("b", "Film"))
        assertTrue(matchCarryOver(old, listOf(row("a", "Film"), row("c", "Film"))).ids.isEmpty())
    }

    @Test fun duplicatePlaylistLocatorsUseTheFinalPersistedRow() {
        val old = row("a", "Before", "LIVE", tvg = "channel")
        val moved = old.copy(id = "b", title = "After")
        assertEquals(mapOf("a" to "b"), matchCarryOver(listOf(old), listOf(moved, moved)).ids)
    }

    @Test fun versionPreferenceIdentityUsesTheSameMinimumIdAsTheVersionPicker() {
        val old = listOf(row("a", "Film", provider = "1", date = "").copy(versionKey = "old-group"),
            row("b", "Film", provider = "2").copy(versionKey = "old-group"))
        val new = listOf(row("c", "New Film", provider = "1").copy(versionKey = "new-group"),
            row("d", "New Film", provider = "2", date = "").copy(versionKey = "new-group"))
        assertEquals(mapOf(old.first().preferenceIdentity() to new.first().preferenceIdentity()),
            carryPreferenceIdentities(old, new, mapOf("a" to "c", "b" to "d")))
    }

    @Test fun playbackKeysCarryRenamedChannelsAndRenumberedProviderEpisodes() {
        val show = row("show", "Show", "SERIES")
        val episode = row("ep", "Show S1E2", "EPISODE", parent = show.id)
        val channel = row("tv", "Before", "LIVE")
        val newShow = show.copy(id = "new-show", title = "New Show")
        val newEpisode = episode.copy(id = "new-ep", title = "New Show S2E3", parent = newShow.id)
        val newChannel = channel.copy(id = "new-tv", title = "After")
        val identities = carryPreferenceIdentities(listOf(show, episode, channel), listOf(newShow, newEpisode, newChannel),
            mapOf(show.id to newShow.id, episode.id to newEpisode.id, channel.id to newChannel.id))
        assertEquals(newChannel.preferenceIdentity(), identities[channel.preferenceIdentity()])
        assertEquals("${newShow.preferenceIdentity()}|2|3", identities["${show.preferenceIdentity()}|1|2"])
    }
}
