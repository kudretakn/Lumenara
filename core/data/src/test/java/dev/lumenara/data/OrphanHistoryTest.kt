package dev.lumenara.data

import dev.lumenara.catalog.contentKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrphanHistoryTest {
    private class MemoryStore : DataStore<StoredPreferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (StoredPreferences) -> StoredPreferences): StoredPreferences =
            transform(data.value).toPreferences().also { data.value = it }
    }

    private fun row(id: String, kind: String = "MOVIE", parent: String = "", title: String = "Film") =
        CarryRow(id, "old", title, "Drama", kind, "", "", parent, id, "", "")

    private fun snapshot(now: Long = System.currentTimeMillis()): OrphanHistory =
        orphanHistory("old", "provider", now, listOf(row("movie")),
            listOf(Mark("movie", favorite = true, listed = true, position = 400, duration = 1000, updated = now)),
            listOf(Member("movie", "Listem")), emptySet())

    @Test fun providerIgnoresCredentialsButSeparatesServersPortsAndSourceTypes() {
        val old = SourceSpec("https://example.test", false, "old-user", "old-pass")
        val same = SourceSpec("https://EXAMPLE.test:443/", false, "new-user", "new-pass")
        assertEquals(orphanProvider(old), orphanProvider(same))
        assertNotEquals(orphanProvider(old), orphanProvider(old.copy(address = "https://other.test")))
        assertNotEquals(orphanProvider(old), orphanProvider(old.copy(address = "https://example.test:8443")))
        assertNotEquals(orphanProvider(old), orphanProvider(old.copy(user = "")))
        assertNotNull(orphanProvider(old.copy(address = "content://document/playlist")))
        assertFalse(orphanProvider(old)!!.contains("example"))
    }

    @Test fun compactSnapshotRetainsEpisodeParentsAndIdentityPeersWithoutLocators() {
        val now = System.currentTimeMillis()
        val parent = row("parent", "SERIES", title = "Show")
        val episode = row("episode", "EPISODE", "parent", "Show S01E02").copy(legacyUrl = "sealed-url")
        val peer = episode.copy(id = "duplicate", providerId = "different")
        val captured = orphanHistory("old", "provider", now,
            listOf(parent, episode, peer, row("unused", title = "Other")),
            listOf(Mark("episode", position = 500), Mark("unrelated", position = 1)), emptyList(), emptySet())
        assertEquals(setOf("parent", "episode", "duplicate"), captured.rows.map { it.id }.toSet())
        assertEquals(listOf("episode"), captured.marks.map { it.id })
        assertTrue(captured.rows.all { it.legacyUrl.isEmpty() })
        val target = episode.copy(id = "new-episode", source = "new", providerId = "changed")
        assertTrue(matchCarryOver(captured.rows, listOf(parent.copy(id = "new-parent"),
            target.copy(parent = "new-parent"))).ids["episode"] == null)
    }

    @Test fun codecPreservesHistoryListemResumeAndMatchesNewCredentialsCatalog() {
        val old = snapshot()
        val encoded = encodeOrphanHistory(listOf(old))
        val restored = decodeOrphanHistory(encoded).single()
        assertEquals(old, restored)
        val target = row("new-movie").copy(source = "new", providerId = "movie")
        val matches = matchCarryOver(restored.rows, listOf(target))
        assertEquals(mapOf("movie" to "new-movie"), matches.ids)
        val mark = mergeCarryMark(restored.marks.single(), null, matches.ids.getValue("movie"))
        assertEquals(400L, mark.position)
        assertEquals(1000L, mark.duration)
        assertTrue(mark.favorite && mark.listed)
        assertEquals("new-movie", mark.id)
        assertEquals("Listem", restored.members.single().name)
    }

    @Test fun multipleDeletionsFromOneProviderMatchIndependentlyAndMergeProgress() {
        val now = System.currentTimeMillis()
        val first = snapshot(now - 1)
        val secondRow = row("other-account-movie").copy(source = "other-account", providerId = "movie")
        val second = orphanHistory("other-account", "provider", now, listOf(secondRow),
            listOf(Mark(secondRow.id, position = 800, duration = 1000, updated = now)), emptyList(), emptySet())
        val target = row("new-movie").copy(source = "new", providerId = "movie")
        val matches = matchOrphanHistories(listOf(first, second), listOf(target))
        assertEquals(mapOf("movie" to "new-movie", secondRow.id to "new-movie"), matches.ids)
        assertEquals(0, matches.ambiguous)
        val restored = (first.marks + second.marks).fold(null as Mark?) { current, old ->
            mergeCarryMark(old, current, matches.ids.getValue(old.id))
        }!!
        assertEquals(800L, restored.position)
        assertTrue(restored.favorite && restored.listed)
    }

    @Test fun compactSnapshotRetainsVersionRepresentativeAndSkipsDurationOnlyMetadata() {
        val chosen = row("z-chosen", title = "Film [4K]").copy(versionKey = "group")
        val representative = row("a-representative", title = "Translated Film").copy(versionKey = "group")
        val unused = row("unused", title = "Other")
        val captured = orphanHistory("old", "provider", System.currentTimeMillis(),
            listOf(chosen, representative, unused),
            listOf(Mark(chosen.id, listed = true), Mark(unused.id, duration = 1000)), emptyList(), emptySet())
        assertEquals(setOf(chosen.id, representative.id), captured.rows.map { it.id }.toSet())
        assertEquals(listOf(chosen.id), captured.marks.map { it.id })
        assertEquals(carryProductionIdentities(listOf(chosen, representative))[chosen.id],
            carryProductionIdentities(captured.rows)[chosen.id])
    }

    @Test fun newestResumeWinsWhileFavoriteAndListedFlagsMerge() {
        val now = System.currentTimeMillis()
        val old = Mark("old", favorite = true, position = 100, duration = 1000, updated = now - 1)
        val current = Mark("new", listed = true, position = 200, duration = 1000, updated = now)
        assertEquals(current.copy(favorite = true), mergeCarryMark(old, current, "new"))
        assertEquals(old.copy(id = "new", listed = true, updated = now + 1),
            mergeCarryMark(old.copy(updated = now + 1), current, "new"))
    }

    @Test fun playlistUrlHashSurvivesSourceIdAndTitleChangesWithoutSavingCredentials() {
        val address = "https://example.test/live/private-user/private-password/17.ts"
        val old = row("old", kind = "LIVE").copy(providerId = "", urlHash = contentKey("orphan-url", address))
        val captured = orphanHistory("old", "provider", System.currentTimeMillis(), listOf(old),
            listOf(Mark(old.id, updated = System.currentTimeMillis())), emptyList(), emptySet())
        val target = old.copy(id = "new", source = "new", title = "Renamed", category = "News")
        assertEquals(mapOf(old.id to target.id), matchOrphanHistories(listOf(captured), listOf(target)).ids)
        val encoded = encodeOrphanHistory(listOf(captured))
        assertFalse(encoded.contains("private-user"))
        assertFalse(encoded.contains("private-password"))
        assertEquals(captured, decodeOrphanHistory(encoded).single())
    }

    @Test fun weakNameMatchesRequireCategoryAndDuplicateHashesDoNotGuess() {
        val old = row("old", kind = "LIVE").copy(providerId = "", urlHash = "hash")
        val captured = orphanHistory("old", "provider", System.currentTimeMillis(), listOf(old),
            listOf(Mark(old.id, favorite = true)), emptyList(), emptySet())
        val otherCategory = old.copy(id = "other", category = "News", urlHash = "different")
        assertTrue(matchOrphanHistories(listOf(captured), listOf(otherCategory)).ids.isEmpty())
        val target = old.copy(id = "new")
        assertTrue(matchOrphanHistories(listOf(captured), listOf(target, target.copy(id = "duplicate"))).ids.isEmpty())
        assertEquals(mapOf(old.id to target.id), matchOrphanHistories(listOf(captured), listOf(target)).ids)
    }

    @Test fun partialRecoveryRetainsUnmatchedHistoryAndAmbiguityPeersForLaterRefresh() {
        val rows = listOf(row("first"), row("second", title = "Other"), row("peer", title = "Other"))
        val captured = orphanHistory("old", "provider", System.currentTimeMillis(), rows,
            listOf(Mark("first", favorite = true), Mark("second", position = 100)), emptyList(), emptySet())
        val partial = pendingOrphanHistory(captured, setOf("first"))!!
        assertEquals(listOf("second"), partial.marks.map { it.id })
        assertEquals(captured.rows, partial.rows)
        val target = row("new", title = "Other").copy(providerId = "changed")
        assertTrue(matchOrphanHistories(listOf(partial), listOf(target)).ids.isEmpty())
        assertEquals(partial, pendingOrphanHistory(partial, emptySet()))
        assertNull(pendingOrphanHistory(partial, setOf("second")))
    }

    @Test fun preferenceOnlyRecoveryRemapsSubtitleSourceAndRecentChannel() = runBlocking {
        val prefs = Preferences(MemoryStore(), { it }, { it })
        val old = row("old-channel", kind = "LIVE")
        prefs.set("subtitle_delay_old|old-channel", "750")
        assertEquals(setOf(old.id), prefs.carryReferences(listOf(old)))
        prefs.set("recent_live", old.id)
        val captured = orphanHistory("old", "provider", System.currentTimeMillis(), listOf(old),
            emptyList(), emptyList(), prefs.carryReferences(listOf(old)))
        val target = old.copy(id = "new-channel", source = "new")
        val matches = matchOrphanHistories(listOf(captured), listOf(target))
        prefs.carryItems(matches.ids, emptyMap(), mapOf("old" to "new"))
        assertEquals("750", prefs.text("subtitle_delay_new|new-channel").first())
        assertEquals("", prefs.text("subtitle_delay_old|old-channel").first())
        assertEquals(target.id, prefs.text("recent_live").first())
        assertNull(pendingOrphanHistory(captured, matches.ids.keys))
    }

    @Test fun earlierWatchNextAliasesSurviveDeletionAndPartialRecovery() {
        val captured = snapshot().copy(redirects = listOf(ItemRedirect("original-movie", "movie")))
        assertEquals(captured, decodeOrphanHistory(encodeOrphanHistory(listOf(captured))).single())
        assertEquals(captured, pendingOrphanHistory(captured, emptySet()))
        val target = row("new-movie").copy(source = "new", providerId = "movie")
        val matches = matchOrphanHistories(listOf(captured), listOf(target))
        assertEquals("new-movie", matches.ids[captured.redirects.single().newId])
        assertNull(pendingOrphanHistory(captured, matches.ids.keys))
    }

    @Test fun snapshotsAreEncryptedExpireAtThirtyDaysAndAreRemovedAfterConsumption() = runBlocking {
        val store = MemoryStore()
        val prefs = Preferences(store, { "encrypted:${it.reversed()}" }, { it.removePrefix("encrypted:").reversed() })
        val now = System.currentTimeMillis()
        val old = snapshot(now)
        prefs.saveOrphanSnapshots(listOf(old))
        val stored = prefs.text(orphanHistorySetting).first()
        assertTrue(stored.startsWith("encrypted:"))
        assertFalse(stored.contains("Listem"))
        assertEquals(listOf(old), prefs.orphanSnapshots(now + orphanHistoryLifetime - 1))
        assertTrue(prefs.orphanSnapshots(now + orphanHistoryLifetime).isEmpty())
        assertEquals("", prefs.text(orphanHistorySetting).first())
        prefs.saveOrphanSnapshots(listOf(old))
        val settingsUndo = prefs.snapshotSettings()
        prefs.saveOrphanSnapshots(emptyList())
        assertTrue(prefs.orphanSnapshots(now).isEmpty())
        prefs.restoreSnapshot(settingsUndo)
        assertEquals(listOf(old), prefs.orphanSnapshots(now))
        assertTrue(old.alive(now - 1))
    }

    @Test fun backupsExcludeOrphansAndRejectInjectedRecoveryState() = runBlocking {
        val prefs = Preferences(MemoryStore(), { "sealed:$it" }, { it.removePrefix("sealed:") })
        val saved = snapshot()
        prefs.saveOrphanSnapshots(listOf(saved))
        prefs.set("theme", "dark")
        assertEquals(listOf(BackupSetting("theme", "string", "dark")), prefs.backupSettings())
        assertFalse(isBackupSetting(orphanHistorySetting))
        prefs.restoreSettings(listOf(BackupSetting(orphanHistorySetting, "string", "foreign-device")),
            emptyMap(), emptyMap())
        assertEquals(listOf(saved), prefs.orphanSnapshots(System.currentTimeMillis()))
    }
}
