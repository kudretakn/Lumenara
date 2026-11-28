package dev.lumenara.data

import androidx.room.withTransaction
import dev.lumenara.catalog.contentKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Conditional fetch policy and source requests; every production body uses the Vault spool. */
internal class ImportCoordinator(
    private val cache: File,
    private val db: LibraryDb,
    private val vault: Vault,
    private val preferences: Preferences,
    private val imports: SourceImports,
    private val userState: UserStateCarry,
    private val catalogImporter: CatalogImporter,
    private val spool: EncryptedSpool,
) {
    private val library = db.library()
    suspend fun add(name: String, spec: SourceSpec, progress: ImportProgress? = null) =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { "Kaynak adını girin." }
            val id = UUID.randomUUID().toString()
            // Cleanup runs under the same lock, including cancellation after the DB commit.
            imports.run(progress, onFailure = { userState.removeUnlocked(id) }) {
                val resolved = resolveSource(spec, progress)
                val source = Source(id, name.trim(), vault.encrypt(resolved.encode()), resolved.allowHttp, 0, 0)
                import(source, resolved, progress = progress)
            }
        }

    suspend fun refresh(id: String, progress: ImportProgress? = null) =
        withContext(Dispatchers.IO) {
            imports.run(progress) {
                val source = library.source(id) ?: return@run
                import(source, SourceSpec.decode(vault.decrypt(source.endpoint)), progress = progress)
            }
        }

    private suspend fun resolveSource(spec: SourceSpec, progress: ImportProgress?): SourceSpec {
        if (spec.user.isEmpty()) return spec
        progress?.report("Sunucu adresi çözümleniyor…")
        val address = Transport.resolveBase(spec.address, spec.allowHttp, currentCoroutineContext()[ImportCalls])
        currentCoroutineContext().ensureActive()
        progress?.resolved(address)
        return spec.copy(address = address)
    }

    suspend fun sourceSpec(id: String): SourceSpec = withContext(Dispatchers.IO) {
        wp16Operation(Wp16Stage.CARRY) { SourceSpec.decode(vault.decrypt(checkNotNull(library.source(id)).endpoint)) }
    }

    suspend fun editSource(id: String, name: String, spec: SourceSpec, progress: ImportProgress? = null) = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Kaynak adını girin." }
        imports.run(progress) {
            val old = library.source(id) ?: throw SourceFailure("Kaynak bulunamadı.")
            val resolved = resolveSource(spec, progress)
            import(old.copy(name = name.trim(), endpoint = vault.encrypt(resolved.encode()), allowHttp = resolved.allowHttp), resolved,
                force = sourceSpec(id) != resolved, progress = progress)
        }
    }

    private suspend fun import(source: Source, spec: SourceSpec, updatePreferences: Boolean = true, force: Boolean = false,
        progress: ImportProgress? = null) {
        val token = maxOf(System.currentTimeMillis(), source.refreshed + 1)
        val key = "source_${source.id}"
        val identity = contentKey("catalog", spec.encode())
        // Pending restore episodes may not exist in Room yet. Reparse even an unchanged
        // top-level catalog so their parents can be prepared and their state retried.
        val pendingRestore = updatePreferences && preferences.wp12PendingRestores().any { pending ->
            pending.data.sources.any { saved -> pending.sources[saved.id] == source.id &&
                saved.spec.address == spec.address && saved.spec.user == spec.user }
        }
        val previous = if (source.refreshed > 0 && !force && !pendingRestore &&
            preferences.text("catalog_identity_${source.id}").first() == identity) preferences.fetchState(key) else FetchState()
        val job = currentCoroutineContext()
        val xtream = if (spec.user.isNotEmpty()) Xtream(spec, job[ImportCalls], spool) else null
        var downloaded: Download? = null
        var snapshot: Xtream.Snapshot? = null
        try {
            if (xtream != null) snapshot = xtream.prepare(cache, previous,
                progress = { progress?.report(it) }) { job.ensureActive() }
            else if (!spec.address.startsWith("content://"))
                downloaded = Transport.download(spec.address, spec.allowHttp, cache, previous,
                    checkActive = { job.ensureActive() }, calls = job[ImportCalls],
                    progress = { progress?.report("Liste indiriliyor · ${it / (1024 * 1024)} MB") }, spool = spool)
            job.ensureActive()
            val body = downloaded
            val catalog = snapshot
            val state = catalog?.state ?: body?.state
            val changed = if (body != null) body.importIfChanged {
                catalogImporter.importChanged(source, spec, token, xtream, catalog, body, updatePreferences, progress)
            } else if (catalog?.unchanged != true) {
                catalogImporter.importChanged(source, spec, token, xtream, catalog, body, updatePreferences, progress)
                true
            } else false
            if (!changed) {
                progress?.report("Katalog düzenleniyor…")
                db.withTransaction {
                    catalogImporter.prepareCatalog(source.id)
                    library.save(source.copy(refreshed = token))
                }
            }
            val providerLimit = catalog?.account?.optJSONObject("user_info")?.optInt("max_connections", 0) ?: 0
            // Restore publishes all settings in one edit, after every source has imported.
            if (updatePreferences) {
                if (providerLimit > 0) preferences.set("provider_${source.id}", providerLimit.toString())
                if (state != null) preferences.setFetchState(key, state)
                preferences.set("catalog_identity_${source.id}", identity)
            }
        } finally {
            snapshot?.close()
            downloaded?.close()
        }
    }

}
