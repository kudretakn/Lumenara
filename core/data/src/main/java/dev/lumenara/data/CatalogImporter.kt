package dev.lumenara.data

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dev.lumenara.catalog.*
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Prepares source-owned stages, then publishes under the caller's existing journal/Room lock. */
internal class CatalogImporter(
    private val context: Context,
    private val db: LibraryDb,
    private val vault: Vault,
    private val metadata: Metadata,
    private val preferences: Preferences,
    private val restoreTransaction: RestoreTransaction,
    private val userState: UserStateCarry,
    private val reader: CatalogReader,
    private val subtitles: SubtitleService,
    private val lock: Mutex,
    private val spool: EncryptedSpool,
    private val retryPending: suspend (String, SourceSpec, List<CarryRow>) -> Unit,
) {
    private val library = db.library()
    private val seriesExpansionCache = SeriesExpansionCache(
        readStamp = { preferences.text("series_expanded_$it").first().toLongOrNull() ?: 0L },
        writeStamp = { id, stamp -> preferences.set("series_expanded_$id", stamp.toString()) },
    )

    @VisibleForTesting
    internal suspend fun setSeriesExpansionStampForTesting(id: String, stamp: Long) =
        withContext(Dispatchers.IO) {
            lock.withLock { seriesExpansionCache.setStampForTesting(id, stamp) }
        }

    suspend fun importChanged(source: Source, spec: SourceSpec, token: Long,
        xtream: Xtream?, snapshot: Xtream.Snapshot?, downloaded: Download?, carryPreferences: Boolean,
        progress: ImportProgress? = null) {
        prepareImportChanged(source, spec, token, xtream, snapshot, downloaded, carryPreferences, progress).use { prepared ->
            if (carryPreferences && prepared.needsJournal) restoreTransaction.run { prepared.commit() }
            else db.withTransaction { prepared.commit() }
            val matches = prepared.matches
            android.util.Log.i("LumenaraPerf", "history_carry_over matched=${matches.ids.size} ambiguous=${matches.ambiguous} unmatched=${matches.unmatched}")
        }
    }

    private suspend fun prepareImportChanged(source: Source, spec: SourceSpec, token: Long,
        xtream: Xtream?, snapshot: Xtream.Snapshot?, downloaded: Download?, carryPreferences: Boolean,
        progress: ImportProgress? = null, restoreItems: List<BackupItem> = emptyList()): Wp12PreparedCatalog {
        // Each prepared source owns its stage until the single restore commit finishes.
        val stageDirectory = wp12StageDirectory(context.cacheDir)
        val staged = CatalogStage(stageDirectory, source.id, token, spool)
        try {
            val job = currentCoroutineContext()
            val carryStart = android.os.SystemClock.elapsedRealtime()
            // Backup imports must never read or consume device-local recovery state.
            val retainedOrphans = if (carryPreferences) preferences.orphanSnapshots(System.currentTimeMillis()) else emptyList()
            val recovering = retainedOrphans.filter { it.provider == orphanProvider(spec) }
            val pendingRestores = if (carryPreferences) preferences.wp12PendingRestores().filter { pending ->
                pending.data.sources.any { saved -> pending.sources[saved.id] == source.id &&
                    saved.spec.address == spec.address && saved.spec.user == spec.user }
            } else emptyList()
            val pendingItems = pendingRestores.flatMap { pending -> pending.data.sources.filter { saved ->
                pending.sources[saved.id] == source.id && saved.spec.address == spec.address && saved.spec.user == spec.user
            }.map { saved -> pending.data.items.filter { it.source == saved.id &&
                (it.id in pending.ids || it.parentAddress.isEmpty()) } } }
            val previousRows = userState.carryRows(source.id, spec.user.isNotEmpty())
            val old = previousRows + recovering.flatMap { it.rows }
            val incoming = ArrayList<CarryRow>()
            val series = HashMap<String, Wp22SeriesLocator>()
            val preparedEpisodeParents = HashSet<String>()
            val restoreEpisodeDurations = HashMap<String, Long>()
            progress?.report("İçerikler hazırlanıyor…")
            staged.write { append ->
                fun stage(row: Item) {
                    append(row)
                    incoming += row.carryRow().let { identity ->
                        if (recovering.isNotEmpty() && spec.user.isEmpty()) identity.copy(
                            urlHash = contentKey("orphan-url", vault.decrypt(row.encryptedUrl))) else identity
                    }
                    if (incoming.size % 250 == 0) progress?.report("İçerikler hazırlanıyor · ${importCount(incoming.size)}")
                    if (row.kind == "SERIES") series[row.id] = Wp22SeriesLocator(row.id, row.title, row.encryptedUrl)
                }
                val grouper = SeriesGrouper()
                fun accept(row: Listing) {
                    job.ensureActive()
                    if (spec.user.isNotEmpty()) stage(item(source.id, token, row))
                    else grouper.accept(row) { stage(item(source.id, token, it)) }
                }
                when {
                    spec.user.isNotEmpty() -> checkNotNull(xtream).read(snapshot, ::accept)
                    spec.address.startsWith("content://") ->
                        context.contentResolver.openInputStream(Uri.parse(spec.address))?.use {
                            Playlist.read(
                                bounded(it).bufferedReader(),
                                null,
                                spec.allowHttp,
                                ::accept,
                            )
                        }
                    else ->
                        checkNotNull(downloaded).read { input, finalUrl ->
                            Playlist.read(
                                input.bufferedReader(),
                                URI(finalUrl),
                                spec.allowHttp,
                                ::accept,
                            )
                        }
                }
                // Xtream episodes are lazy. Rebuild only already-expanded series whose locator
                // changed, before pruning their old parents. A failed fetch leaves the old catalog intact.
                if (xtream != null) {
                    val expanded = previousRows.filter { it.kind == "EPISODE" }.map { it.parent }.toSet() +
                        recovering.flatMap { orphan ->
                            val pending = orphan.pendingIds()
                            orphan.rows.filter { it.kind == "EPISODE" && it.id in pending }.map { it.parent }
                        }
                    val targets = incoming.filter { it.kind == "SERIES" }
                    val matches = matchCarryOver(previousRows.filter { it.kind == "SERIES" }, targets).ids +
                        recovering.flatMap { orphan -> matchCarryOver(orphan.rows.filter { it.kind == "SERIES" },
                            targets, categoryFallback = true).ids.entries }.associate { it.key to it.value }
                    val restoreParents = wp12RestoreParentIds(restoreItems, source.id, spec, incoming) +
                        pendingItems.flatMap { wp12RestoreParentIds(it, source.id, spec, incoming) }
                    for (to in matches.filterKeys { it in expanded }.values.toSet() + restoreParents) {
                        val parent = series.getValue(to)
                        preparedEpisodeParents += parent.id
                        streamEpisodes(xtream, parent, source.id, token, { job.ensureActive() }) { episode, duration ->
                            if (to in restoreParents && duration > 0) restoreEpisodeDurations[episode.id] = duration
                            stage(episode)
                        }
                    }
                }
            }
            val incomingIds = incoming.map { it.id }.toSet()
            val refreshedParents = preparedEpisodeParents + incoming.filter { it.kind == "EPISODE" }.map { it.parent }
            val existingMatches = matchCarryOver(previousRows.filterNot {
                it.kind == "EPISODE" && it.parent in incomingIds && it.parent !in refreshedParents
            }, incoming)
            val orphanMatches = matchOrphanHistories(recovering, incoming)
            val matches = CarryMatch(existingMatches.ids + orphanMatches.ids,
                existingMatches.ambiguous + orphanMatches.ambiguous, existingMatches.unmatched + orphanMatches.unmatched)
            // M3U supplies the complete episode catalog. Missing episodes must be pruned even
            // when marked; matched locator changes have already carried their state forward.
            // Xtream episodes are lazy, so absence from its top-level catalog proves nothing.
            val playlistEpisodes = if (spec.user.isEmpty())
                old.filter { it.kind == "EPISODE" }.map { it.id }.toSet() else emptySet()
            StartupPerf.elapsed("refresh_history_carry_over", carryStart)
            val commit: suspend () -> Unit = {
                // A full import supplies current assignments; staged snapshots must not
                // overwrite refreshed metadata in a later migration batch.
                db.versionBackfill().discardSource(source.id)
                library.save(source)
                var imported = 0
                progress?.report("İçe aktarılıyor · 0 / ${importCount(incoming.size)}")
                staged.read { batch ->
                    job.ensureActive()
                    // Keep late metadata matches on unchanged streams, even if Android evicted
                    // their JSON cache before this provider refresh.
                    val previous = library.importedVersionSeeds(batch.map { it.id }).associateBy { it.id }
                    library.writeBatch(batch.map { row ->
                        val old = previous[row.id]
                        val retained = if (row.tmdbId().isEmpty() && old != null &&
                            (row.title == old.title && row.year == old.year ||
                                searchable(productionName(row.title, row.year).title) == searchable(productionName(old.title, old.year).title)))
                            row.withTmdbMatch(TmdbMatch(old.tmdbId(), old.year)) else row
                        val matched = retained.withTmdbMatch(metadata.cachedMatch(retained)).copy(
                            channelNumber = old?.channelNumber ?: 0, safeChannelNumber = old?.safeChannelNumber ?: 0)
                        if (old != null && old.title == matched.title && old.year == matched.year &&
                            old.search == matched.search && old.kind == matched.kind) matched.copy(
                            versionKey = old.versionKey, isRepresentative = old.isRepresentative,
                            displayTitle = old.displayTitle, displayYear = old.displayYear,
                            versionRank = old.versionRank, cleanTitle = old.cleanTitle, sortKey = old.sortKey)
                        else matched.copy(versionKey = row.id, displayTitle = row.title, displayYear = row.year)
                    })
                    imported += batch.size
                    progress?.report("İçe aktarılıyor · ${importCount(imported)} / ${importCount(incoming.size)}")
                }
                val preferenceReferences = preferences.carryReferences(old)
                val excluded = context.getSharedPreferences("watch_next_state", Context.MODE_PRIVATE)
                    .getStringSet("excluded", emptySet()).orEmpty().toSet()
                val orphanReferences = recovering.flatMap { it.pendingIds() }.toSet()
                // Explicitly prepared parents can keep the same URL. Preserve their unresolved
                // local history and ambiguity peers before pruning stale episode locators.
                val pendingEpisodes = if (preparedEpisodeParents.isEmpty()) null else orphanHistory(
                    source.id, "", token, previousRows, emptyList(), emptyList(),
                    library.carryProtectedIds(source.id).toSet() + preferenceReferences + excluded)
                carryRoomState(db, matches.ids, preferenceReferences + excluded + orphanReferences)
                // Preserve the duration seed previously supplied by forced restore expansion.
                for ((id, duration) in restoreEpisodeDurations) {
                    val mark = library.mark(id)
                    if ((mark?.duration ?: 0) <= 0) library.save((mark ?: Mark(id)).copy(duration = duration))
                }
                for (orphan in recovering) {
                    for (mark in orphan.marks) {
                        val target = orphanMatches.ids[mark.id] ?: continue
                        library.save(mergeCarryMark(mark, library.mark(target), target))
                    }
                    for (member in orphan.members) {
                        val target = orphanMatches.ids[member.id] ?: continue
                        if (library.member(target) == null) library.collection(member.copy(id = target))
                    }
                    for (redirect in orphan.redirects) {
                        val target = orphanMatches.ids[redirect.newId] ?: continue
                        if (redirect.oldId != target && library.redirectedId(redirect.oldId) == null)
                            library.redirect(redirect.copy(newId = target))
                    }
                }
                // Keep other unresolved user records for a future/manual retry, including
                // lazy episode parents, rather than guessing or cascading their removal.
                val protected = (library.carryProtectedIds(source.id) + preferenceReferences)
                    .filter { it !in matches.ids && it !in incomingIds && it !in playlistEpisodes }.toMutableSet()
                pendingEpisodes?.let { pending ->
                    val parents = previousRows.associate { it.id to it.parent }
                    protected += wp03UnresolvedEpisodeIds(pending, incomingIds, matches.ids.keys)
                        .filter { parents[it] in preparedEpisodeParents }
                }
                old.filter { it.id in protected && it.parent.isNotBlank() }.forEach { protected += it.parent }
                protected.removeAll(incomingIds)
                protected.toList().chunked(900).forEach { library.retainCarryRows(it, token) }
                if (spec.user.isEmpty()) library.prunePlaylist(source.id, token)
                else {
                    for (parent in preparedEpisodeParents) library.pruneEpisodes(parent, token)
                    library.prune(source.id, token)
                }
                library.orphans(source.id)
                protected.toList().chunked(900).forEach { library.isolateCarryRows(it, token - 1) }
                progress?.report("Katalog düzenleniyor…")
                prepareCatalog(source.id, token)
                if (carryPreferences && pendingRestores.isNotEmpty()) retryPending(source.id, spec, incoming)
                if (carryPreferences && matches.ids.isNotEmpty()) {
                    val current = library.carryRows(source.id)
                    val subtitleKeys = preferences.carryItems(matches.ids, carryPreferenceIdentities(old, current, matches.ids),
                        old.filter { it.id in matches.ids }.associate { it.source to source.id })
                    subtitles.cache.carryKeys(subtitleKeys)
                }
                if (recovering.isNotEmpty()) preferences.saveOrphanSnapshots(
                    retainedOrphans.filterNot { it in recovering } + recovering.mapNotNull {
                        pendingOrphanHistory(it, orphanMatches.ids.keys)
                    })
                job.ensureActive()
                library.save(source.copy(refreshed = token, count = library.count(source.id)))
            }
            return Wp12PreparedCatalog(incoming, matches, matches.ids.isNotEmpty() || recovering.isNotEmpty() || pendingRestores.isNotEmpty(), commit) {
                staged.close()
                stageDirectory.deleteRecursively()
            }
        } catch (failure: Throwable) {
            staged.close()
            stageDirectory.deleteRecursively()
            throw failure
        }
    }

    suspend fun prepareRestoreCatalog(source: Source, spec: SourceSpec,
        items: List<BackupItem>): Wp12PreparedCatalog {
        val job = currentCoroutineContext()
        val xtream = if (spec.user.isNotEmpty()) Xtream(spec, job[ImportCalls], spool) else null
        var downloaded: Download? = null
        var snapshot: Xtream.Snapshot? = null
        try {
            if (xtream != null) snapshot = xtream.prepare(context.cacheDir, FetchState(), checkActive = { job.ensureActive() })
            else if (!spec.address.startsWith("content://")) downloaded = Transport.download(
                spec.address, spec.allowHttp, context.cacheDir, checkActive = { job.ensureActive() }, calls = job[ImportCalls], spool = spool)
            return prepareImportChanged(source, spec, maxOf(System.currentTimeMillis(), source.refreshed + 1),
                xtream, snapshot, downloaded, carryPreferences = false, restoreItems = items)
        } finally {
            snapshot?.close()
            downloaded?.close()
        }
    }

    /** Same digest and computation version consult only the durable metadata queue. */
    suspend fun prepareCatalog(source: String, generation: Long = -1) {
        Wp22Incremental(db).prepare(source, bodyChanged = generation >= 0, generation = generation)
    }

    private fun item(source: String, token: Long, entry: Listing) =
        Item(
            contentKey(source, entry.url),
            source,
            entry.title,
            entry.group,
            entry.kind.name,
            entry.tvgId,
            vault.encrypt(entry.url),
            searchable("${entry.title} ${entry.group} ${entry.tvgId}") +
                (entry.tmdbId.toLongOrNull()?.takeIf { it > 0 }?.let { " [tmdb:$it]" } ?: ""),
            token,
            entry.logo.takeIf { it.startsWith("https://") }?.let(vault::encrypt).orEmpty(),
            parent = entry.parentUrl.takeIf(String::isNotBlank)?.let { contentKey(source, it) }.orEmpty(),
            summary = entry.summary,
            year = entry.year,
            genre = entry.genre,
            rating = entry.rating,
            language = entry.language,
            castNames = entry.cast,
            archive =
                entry.archive
                    .takeIf { it.isNotEmpty() && entry.days > 0 }
                    ?.let(vault::encrypt)
                    .orEmpty(),
            archiveDays = entry.days,
            archiveZone = entry.zone,
            introStartMs = entry.introStartMs,
            introEndMs = entry.introEndMs,
            backdrop =
                entry.backdrop.takeIf { it.startsWith("https://") }?.let(vault::encrypt).orEmpty(),
            providerId = entry.providerId,
            tvgName = entry.tvgName,
            tvgGroup = entry.tvgGroup,
        ).let { it.copy(versionKey = it.id, displayTitle = it.title, displayYear = it.year) }

    /** Keep sorted-then-distinct URL semantics without retaining a Listing/Item list.
     * Only the best season/episode and duration per identity stay in memory; payloads are sealed.
     */
    private fun streamEpisodes(xtream: Xtream, parent: Wp22SeriesLocator, source: String, token: Long,
        checkActive: () -> Unit, emit: (Item, Long) -> Unit) {
        val directory = wp12StageDirectory(context.cacheDir)
        val choices = HashMap<String, Wp22EpisodeChoice>()
        try {
            CatalogStage(directory, source, token, spool).use { stage ->
                stage.write { append ->
                    append(Item(parent.id, source, parent.title, "", "SERIES", "", parent.encryptedUrl, "", token, ""))
                    xtream.readEpisodes(vault.decrypt(parent.encryptedUrl), parent.title) { listing ->
                        checkActive()
                        val episode = item(source, token, listing).copy(parent = parent.id)
                        val name = episodeName(episode.title, episode.category)
                        val choice = Wp22EpisodeChoice(name.season, name.episode, listing.durationMs)
                        if (choice.precedes(choices[episode.id])) choices[episode.id] = choice
                        append(episode)
                    }
                }
                val emitted = HashSet<String>()
                stage.read { rows -> rows.filter { it.kind == "EPISODE" }.forEach { episode ->
                    checkActive()
                    val choice = choices.getValue(episode.id)
                    val name = episodeName(episode.title, episode.category)
                    if (name.season == choice.season && name.episode == choice.episode && emitted.add(episode.id))
                        emit(episode, choice.duration)
                } }
            }
        } finally { directory.deleteRecursively() }
    }

    suspend fun expand(series: Item): List<Item> =
        withContext(Dispatchers.IO) {
            wp16Operation(Wp16Stage.IMPORT) { lock.withLock { expandUnlocked(series) } }
        }

    // The caller owns the repository lock; forced expansion bypasses a warm episode cache.
    private suspend fun expandUnlocked(series: Item, force: Boolean = false): List<Item> {
        val source = library.source(series.source) ?: return emptyList()
        val spec = SourceSpec.decode(vault.decrypt(source.endpoint))
        if (spec.user.isEmpty()) return library.episodeSnapshot(series.id)
        suspend fun fetch(): List<Item> {
            val job = currentCoroutineContext()
            val token = maxOf(System.currentTimeMillis(), library.wp22EpisodeGeneration(series.id) + 1)
            val oldRows = userState.legacyCarryRows(library.wp22EpisodeCarry(series.id) + series.carryRow().copy(
                legacyUrl = if (series.providerId.isEmpty()) series.encryptedUrl else ""))
            val parent = oldRows.single { it.id == series.id }
            val incoming = ArrayList<CarryRow>()
            val durations = HashMap<String, Long>()
            val seen = HashSet<String>()
            val directory = wp12StageDirectory(context.cacheDir)
            try {
                CatalogStage(directory, source.id, token, spool).use { stage ->
                    stage.write { append ->
                        // A parent sentinel lets an explicitly empty episode response use the
                        // existing encrypted stage's nonempty contract. It is never published.
                        append(series)
                        streamEpisodes(Xtream(spec, job[ImportCalls], spool),
                            Wp22SeriesLocator(series.id, series.title, series.encryptedUrl), source.id, token,
                            { job.ensureActive() }) { episode, duration ->
                            seen += episode.id
                            incoming += episode.carryRow()
                            if (duration > 0) durations[episode.id] = duration
                            append(episode)
                        }
                    }
                    val matches = wp03EpisodeMatches(parent, oldRows, incoming)
                    val commit: suspend () -> Unit = {
                        stage.read { batch ->
                            job.ensureActive()
                            library.writeBatch(batch.filter { it.kind == "EPISODE" })
                        }
                        val preferenceReferences = preferences.carryReferences(oldRows)
                        val excluded = context.getSharedPreferences("watch_next_state", Context.MODE_PRIVATE)
                            .getStringSet("excluded", emptySet()).orEmpty().toSet()
                        val references = preferenceReferences + excluded + library.redirects().map { it.newId }
                        val pending = orphanHistory(source.id, "", token, oldRows, emptyList(), emptyList(),
                            library.carryProtectedIds(source.id).toSet() + references)
                        carryRoomState(db, matches.ids, references)
                        val siblings = if (incoming.isEmpty()) emptyList() else reader.allVersions(series)
                            .filter { it.id != series.id }.flatMap { library.wp22EpisodeCarry(it.id) }
                        val marks = (incoming.asSequence().map { it.id } + siblings.asSequence().map { it.id })
                            .distinct().toList().chunked(900).flatMap { library.marksByIds(it) }.associateBy { it.id }
                        val shared = wp03SiblingMarks(incoming, siblings, marks)
                        incoming.forEach { episode ->
                            val existing = marks[episode.id]
                            var mark = shared[episode.id]?.let { mergeCarryMark(it, existing, episode.id) }
                                ?: existing ?: Mark(episode.id)
                            val duration = durations[episode.id] ?: 0
                            if (mark.duration <= 0 && duration > 0) mark = mark.copy(duration = duration)
                            if (mark != existing && (existing != null || shared[episode.id] != null || mark.duration > 0))
                                library.save(mark)
                        }
                        wp03UnresolvedEpisodeIds(pending, seen, matches.ids.keys)
                            .chunked(900).forEach { library.retainCarryRows(it, token) }
                        library.pruneEpisodes(series.id, token)
                        if (!force && matches.ids.isNotEmpty()) {
                            val subtitleKeys = preferences.carryItems(matches.ids,
                                carryPreferenceIdentities(oldRows, incoming + parent, matches.ids))
                            subtitles.cache.carryKeys(subtitleKeys)
                        }
                    }
                    if (!force && matches.ids.isNotEmpty()) restoreTransaction.run { commit() }
                    else db.withTransaction { commit() }
                }
            } finally { directory.deleteRecursively() }
            return library.episodeSnapshot(series.id)
        }
        return if (force) fetch() else seriesExpansionCache.get(series.id,
            cached = { library.episodeSnapshot(series.id) }, fetch = { fetch() })
    }

}
