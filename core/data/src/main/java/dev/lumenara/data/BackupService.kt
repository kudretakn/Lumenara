package dev.lumenara.data

import androidx.room.withTransaction
import dev.lumenara.catalog.contentKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun wp16RestoreTransaction(db: LibraryDb, vault: Vault, preferences: Preferences,
    metadata: Metadata): RestoreTransaction = RestoreTransaction(object : RestoreStorage {
        override suspend fun pending() = db.restoreJournal().pending()
        override suspend fun snapshot(): String = vault.encrypt(JSONObject()
            .put("settings", preferences.snapshotSettings())
            .put("tmdb", metadata.encryptedToken() ?: JSONObject.NULL).toString())
        override suspend fun savePending(snapshot: String) {
            db.restoreJournal().save(RestorePending(snapshot = snapshot))
            // Android WAL normally uses synchronous=NORMAL. A FULL checkpoint fsyncs the undo
            // record before DataStore can publish anything, even if power is lost before Room
            // commits. A busy checkpoint aborts before restore writes; the journal remains safe.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "Geri yükleme günlüğü kaydedilemedi." }
            }
        }
        override suspend fun restoreSnapshot(snapshot: String) {
            val old = JSONObject(vault.decrypt(snapshot))
            preferences.restoreSnapshot(old.getString("settings"))
            metadata.restoreEncryptedToken(if (old.isNull("tmdb")) null else old.getString("tmdb"))
        }
        override suspend fun clearPending() = db.restoreJournal().clear()
        override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
    })

internal class BackupService(
    private val db: LibraryDb,
    private val lock: kotlinx.coroutines.sync.Mutex,
    private val vault: Vault,
    private val preferences: Preferences,
    private val metadata: Metadata,
    private val restoreTransaction: RestoreTransaction,
    private val prepare: suspend (Source, SourceSpec, List<BackupItem>) -> Wp12PreparedCatalog,
    private val refreshGuides: suspend (Source) -> Unit,
) {
    suspend fun createBackup(password: CharArray): ByteArray = withContext(Dispatchers.IO) {
        lock.withLock { BackupCodec.encrypt(wp16Operation(Wp16Stage.BACKUP) { snapshot() }, password) }
    }

    suspend fun previewBackup(bytes: ByteArray, password: CharArray): BackupPreview = withContext(Dispatchers.IO) {
        lock.withLock { BackupPreview(BackupCodec.decrypt(bytes, password)) }
    }

    suspend fun restoreBackup(preview: BackupPreview, schedule: suspend (Long) -> Unit): BackupRestoreResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val result = wp16Operation(Wp16Stage.BACKUP) { restore(preview.data) }
            // Scheduling is follow-up work; its failure must not report a committed restore as failed.
            try { schedule(preferences.text("hours", "12").first().toLongOrNull()?.coerceIn(1L, 168L) ?: 12L) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* The saved interval will be scheduled on the next launch. */ }
            result
        }
    }

    suspend fun snapshot(): BackupData = db.withTransaction {
        val library = db.library()
        val sources = library.snapshot()
        val marks = library.backupMarks()
        val mappings = db.guide().backupMappings()
        val settings = preferences.backupSettings()
        val recent = settings.firstOrNull { it.name == "recent_live" }?.value?.lines().orEmpty()
        val delays = settings.filter { it.name.startsWith("subtitle_delay_") }
            .mapNotNull { it.name.removePrefix("subtitle_delay_").substringAfter('|', "").takeIf(String::isNotBlank) }
        val redirects = library.redirects()
        val selected = settings.filter { it.name.startsWith("version_") && it.value.startsWith("id:") }.map { it.value.removePrefix("id:") }
        val ids = (marks.map { it.id } + mappings.map { it.item } + recent + delays + selected + redirects.map { it.newId }).distinct()
        val items = ids.mapNotNull { library.item(it) }.map { item ->
            BackupItem(item.id, item.source, vault.decrypt(item.encryptedUrl),
                library.item(item.parent)?.let { vault.decrypt(it.encryptedUrl) }.orEmpty(),
                item.providerId, item.tvgName, item.tvgGroup, item.kind,
                library.item(item.parent)?.providerId.orEmpty())
        }
        val validItems = items.map { it.id }.toSet()
        val validSettings = settings.map {
            if (it.name == "recent_live") it.copy(value = it.value.lines().filter { id -> id in validItems }.joinToString("\n")) else it
        }
        BackupData(validSettings, sources.map {
            BackupSource(it.id, it.name, SourceSpec.decode(vault.decrypt(it.endpoint)))
        }, sources.flatMap { db.guide().feeds(it.id) }.map {
            BackupFeed(it.id, it.source, vault.decrypt(it.encryptedAddress))
        }, items, marks, mappings, metadata.backupToken(), redirects)
    }

    suspend fun restore(data: BackupData): BackupRestoreResult {
        // Revalidate at the write boundary as well as during decrypt/preview, before even journaling.
        BackupCodec.validate(data)
        val library = db.library()
        val sourceIds = HashMap<String, String>()
        val itemIds = HashMap<String, String>()
        val feedIds = HashMap<String, String>()
        var skipped = 0
        var guideFailures = 0
        val prepared = ArrayList<Wp12PreparedCatalog>()
        try {
            restoreTransaction.prepareAndRun(prepare = {
                val existing = library.snapshot().map { it to SourceSpec.decode(vault.decrypt(it.endpoint)) }.toMutableList()
                val usedTargets = HashSet<String>()
                for (saved in data.sources) {
                    val match = wp12RestoreSource(saved, existing, usedTargets)
                    val id = match?.id ?: saved.id.takeIf { old -> existing.none { it.first.id == old } } ?: UUID.randomUUID().toString()
                    usedTargets.add(id)
                    val source = Source(id, saved.name, vault.encrypt(saved.spec.encode()), saved.spec.allowHttp, 0, 0)
                    sourceIds[saved.id] = id
                    val items = data.items.filter { it.source == saved.id }
                    val catalog = prepare(source, saved.spec, items)
                    prepared += catalog
                    itemIds += wp12MatchRestoreItems(items, id, saved.spec, catalog.rows)
                    existing.removeAll { it.first.id == id }
                    existing.add(source to saved.spec)
                }
                skipped = data.items.count { it.id !in itemIds }
                // No networking or episode expansion below this boundary. Preparation failures
                // have not written Room, DataStore or the durable undo journal.
            }, commit = {
                for (catalog in prepared) catalog.commit()
                for (saved in data.feeds) {
                    val source = sourceIds.getValue(saved.source)
                    val id = contentKey(source, saved.address)
                    feedIds[saved.id] = id
                    db.guide().put(Feed(id, source, vault.encrypt(saved.address)))
                }
                for (saved in data.items) itemIds[saved.id]?.let { id ->
                    library.item(id)?.let { restored ->
                        val enriched = restored.copy(providerId = restored.providerId.ifEmpty { saved.providerId },
                            tvgName = restored.tvgName.ifEmpty { saved.tvgName }, tvgGroup = restored.tvgGroup.ifEmpty { saved.tvgGroup })
                        if (enriched != restored) library.save(listOf(enriched))
                    }
                }
                for (saved in data.marks) itemIds[saved.id]?.let { library.save(saved.copy(id = it)) }
                for (saved in data.redirects) itemIds[saved.newId]?.let { target ->
                    if (!library.exists(saved.oldId)) {
                        library.redirectTarget(saved.oldId, target)
                        library.redirect(ItemRedirect(saved.oldId, target))
                    }
                }
                for (saved in data.mappings) itemIds[saved.item]?.let {
                    db.guide().map(saved.copy(item = it, feed = feedIds.getValue(saved.feed)))
                }
                preferences.restoreSettings(data.settings, sourceIds, itemIds)
                metadata.configure(data.tmdbToken)
                preferences.saveWp12PendingRestore(data, sourceIds, data.items.map { it.id }.toSet() - itemIds.keys)
            })
        } finally {
            prepared.asReversed().forEach { it.close() }
        }
        // Programme listings are a rebuildable cache, excluded from the backup. Refresh them
        // after the restore commits; guide failures are reported separately from restore failure.
        for (id in sourceIds.values.distinct()) {
            try { library.source(id)?.let { refreshGuides(it) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { guideFailures++ }
        }
        return BackupRestoreResult(skipped, guideFailures)
    }

    /** Called only by the normal import commit, under its existing settings undo journal. */
    suspend fun retryPending(source: String, spec: SourceSpec, incoming: List<CarryRow>) {
        val pending = preferences.wp12PendingRestores()
        val mappedItems = db.guide().backupMappings().map { it.item }.toMutableSet()
        var changed = false
        val remaining = pending.mapNotNull { entry ->
            val ids = linkedMapOf<String, String>()
            for (saved in entry.data.sources) {
                if (entry.sources[saved.id] != source || saved.spec.address != spec.address || saved.spec.user != spec.user) continue
                ids += wp12MatchRestoreItems(entry.data.items.filter { it.source == saved.id }, source, spec, incoming)
                    .filterKeys { it in entry.ids }
            }
            if (ids.isEmpty()) return@mapNotNull entry
            changed = true
            val library = db.library()
            for (saved in entry.data.marks) ids[saved.id]?.let { library.save(mergeCarryMark(saved, library.mark(it), it)) }
            val feeds = entry.data.feeds.associateBy { it.id }
            for (saved in entry.data.mappings) ids[saved.item]?.let { item ->
                val feed = feeds.getValue(saved.feed)
                val targetFeed = contentKey(source, feed.address)
                db.guide().put(Feed(targetFeed, source, vault.encrypt(feed.address)))
                if (mappedItems.add(item)) db.guide().map(saved.copy(item = item, feed = targetFeed))
            }
            for (saved in entry.data.redirects) ids[saved.newId]?.let { target ->
                if (!library.exists(saved.oldId) && saved.oldId != target) library.redirect(saved.copy(newId = target))
            }
            preferences.restoreWp12ItemSettings(entry.data.settings, entry.sources, ids)
            entry.copy(ids = entry.ids - ids.keys).takeIf { it.ids.isNotEmpty() }
        }
        if (changed) preferences.saveWp12PendingRestores(remaining)
    }

}
