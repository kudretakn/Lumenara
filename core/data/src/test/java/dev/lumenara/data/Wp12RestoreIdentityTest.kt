package dev.lumenara.data

import dev.lumenara.catalog.contentKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Wp12RestoreIdentityTest {
    private val spec = SourceSpec("https://provider.invalid", false, "account", "password")
    private fun saved(address: String, provider: String = "", kind: String = "", parent: String = "",
        parentProvider: String = "", source: String = "old") = BackupItem(contentKey(source, address), source,
        address, parent, providerId = provider, kind = kind, parentProviderId = parentProvider)
    private fun row(address: String, provider: String, kind: String = "MOVIE", parent: String = "", source: String = "new") =
        CarryRow(contentKey(source, address), source, "Title", "Group", kind, "", "", parent, provider, "", "")

    @Test fun providerIdentityPreservesChangedMovieAndLiveLocatorsInTheirKindAndSource() {
        val movie = saved("https://old.invalid/movie/u/p/17.mp4", "17", "MOVIE")
        val live = saved("https://old.invalid/live/u/p/17.ts", "17", "LIVE")
        val targets = listOf(row("https://new.invalid/movie/u/q/17.mkv", "17"),
            row("https://new.invalid/live/u/q/17.m3u8", "17", "LIVE"),
            row("https://else.invalid/movie/u/q/17.mkv", "17", source = "other"))
        assertEquals(mapOf(movie.id to targets[0].id, live.id to targets[1].id),
            wp12MatchRestoreItems(listOf(movie, live), "new", spec, targets))
    }

    @Test fun oldestV1WithoutIdentityFieldsDerivesOnlyKnownXtreamPaths() {
        val movie = saved("https://provider.invalid/movie/account/old-password/17.mp4")
        val target = row("https://provider.invalid/movie/account/new-password/17.mkv", "17")
        assertEquals(mapOf(movie.id to target.id), wp12MatchRestoreItems(listOf(movie), "new", spec, listOf(target)))
        val unknown = saved("https://provider.invalid/custom/17.mp4")
        assertTrue(wp12MatchRestoreItems(listOf(unknown), "new", spec, listOf(target)).isEmpty())
    }

    @Test fun episodesResolveSyntheticParentsBeforeProviderIds() {
        val parent = "https://provider.invalid/player_api.php?password=old&series_id=10"
        val episode = saved("https://provider.invalid/series/u/old/101.mp4", "10:101", parent = parent)
        val newParent = row("https://provider.invalid/player_api.php?password=new&series_id=10", "10", "SERIES")
        val newEpisode = row("https://provider.invalid/series/u/new/101.mkv", "10:101", "EPISODE", newParent.id)
        assertEquals(setOf(newParent.id), wp12RestoreParentIds(listOf(episode), "new", spec, listOf(newParent)))
        val seriesOnly = saved(parent, "10", "SERIES")
        assertTrue(wp12RestoreParentIds(listOf(seriesOnly), "new", spec, listOf(newParent)).isEmpty())
        assertEquals(mapOf(episode.id to newEpisode.id),
            wp12MatchRestoreItems(listOf(episode), "new", spec, listOf(newParent, newEpisode)))
    }

    @Test fun matchingEpisodeProviderUnderWrongOrAmbiguousParentNeverRestores() {
        val episode = saved("https://old.invalid/series/u/p/101.mp4", "10:101", "EPISODE",
            "https://old.invalid/player_api.php?series_id=10", "10")
        val a = row("https://new.invalid/series-info-a", "10", "SERIES")
        val b = row("https://new.invalid/series-info-b", "10", "SERIES")
        val child = row("https://new.invalid/101.mkv", "10:101", "EPISODE", a.id)
        assertTrue(wp12MatchRestoreItems(listOf(episode), "new", spec, listOf(a, b, child)).isEmpty())
        val wrong = child.copy(parent = "another-series")
        assertTrue(wp12MatchRestoreItems(listOf(episode), "new", spec, listOf(a, wrong)).isEmpty())
    }

    @Test fun providerDuplicatesOnEitherSideRemainPendingEvenAfterAnExactPeerMatches() {
        val a = saved("https://provider.invalid/a", "17", "MOVIE")
        val b = saved("https://provider.invalid/b", "17", "MOVIE")
        val target = row(a.address, "17")
        assertEquals(mapOf(a.id to target.id), wp12MatchRestoreItems(listOf(a, b), "new", spec, listOf(target)))
        assertTrue(wp12MatchRestoreItems(listOf(a), "new", spec,
            listOf(row("https://new.invalid/a", "17"), row("https://new.invalid/b", "17"))).isEmpty())
    }

    @Test fun playlistTvgRequiresUniqueNameAndGroupAndNeverUsesTitleAlone() {
        val playlist = spec.copy(user = "", password = "")
        val old = saved("https://provider.invalid/old", kind = "LIVE").copy(tvgName = "Channel", tvgGroup = "News")
        val target = row("https://provider.invalid/new", "", "LIVE").copy(tvgName = "Channel", tvgGroup = "News")
        assertEquals(mapOf(old.id to target.id), wp12MatchRestoreItems(listOf(old), "new", playlist, listOf(target)))
        assertTrue(wp12MatchRestoreItems(listOf(old), "new", playlist, listOf(target.copy(tvgGroup = "Other"))).isEmpty())
        assertTrue(wp12MatchRestoreItems(listOf(old.copy(tvgName = "")), "new", playlist, listOf(target)).isEmpty())
    }

    @Test fun sourceMatchingRequiresEndpointAndAccountAndNeverChoosesFirstDuplicate() {
        val backup = BackupSource("old", "Saved", spec)
        fun source(id: String) = Source(id, "Existing", "sealed", false, 0, 0)
        val a = source("a") to spec
        val b = source("b") to spec
        assertNull(wp12RestoreSource(backup, listOf(a, b), emptySet()))
        assertEquals(a.first, wp12RestoreSource(backup, listOf(a), emptySet()))
        assertNull(wp12RestoreSource(backup, listOf(source("old") to spec.copy(user = "other")), emptySet()))
        assertNull(wp12RestoreSource(backup, listOf(a), setOf("a")))
        val exact = source("old") to spec.copy(password = "changed")
        assertEquals(exact.first, wp12RestoreSource(backup, listOf(a, exact), emptySet()))
    }

    @Test fun thousandEpisodeLocatorChangesKeepEveryReferenceUnderTheirParent() {
        val parent = "https://provider.invalid/player_api.php?series_id=10"
        val incomingParent = row(parent, "10", "SERIES")
        val items = (1..1000).map { n -> saved("https://provider.invalid/series/u/old/$n.mp4", "10:$n",
            "EPISODE", parent, "10") }
        val incoming = listOf(incomingParent) + (1..1000).map { n ->
            row("https://provider.invalid/series/u/new/$n.mkv", "10:$n", "EPISODE", incomingParent.id) }
        val ids = wp12MatchRestoreItems(items, "new", spec, incoming)
        assertEquals(1000, ids.size)
        items.zip(incoming.drop(1)).forEach { (old, new) -> assertEquals(new.id, ids[old.id]) }
    }

    @Test fun optionalIdentityFieldsKeepBackupWireVersionOneReadable() {
        val item = saved("https://provider.invalid/movie/u/p/17.mp4", "17", "MOVIE")
        val data = BackupData(emptyList(), listOf(BackupSource("old", "Saved", spec)), emptyList(),
            listOf(item), listOf(Mark(item.id, favorite = true)), emptyList())
        val wire = BackupCodec.encode(data)
        assertEquals(1, wire.getInt("version"))
        val entry = wire.getJSONArray("items").getJSONObject(0)
        listOf("providerId", "tvgName", "tvgGroup", "kind", "parentProviderId").forEach { entry.remove(it) }
        wire.remove("redirects")
        assertEquals(data.copy(items = listOf(item.copy(providerId = "", kind = ""))), BackupCodec.decode(JSONObject(wire.toString())))
    }
}
