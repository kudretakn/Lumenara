package dev.lumenara.data

import android.content.Context
import dev.lumenara.catalog.*
import java.security.GeneralSecurityException
import org.json.JSONException

/** Locator migration and local deletion recovery retain their existing matching/journal rules. */
internal class UserStateCarry(
    private val context: Context,
    private val db: LibraryDb,
    private val vault: Vault,
    private val preferences: Preferences,
    private val restoreTransaction: RestoreTransaction,
) {
    private val library = db.library()
    suspend fun carryRows(source: String, xtream: Boolean): List<CarryRow> {
        val rows = library.carryRows(source, legacy = xtream)
        return if (xtream) legacyCarryRows(rows) else rows
    }

    fun legacyCarryRows(rows: List<CarryRow>): List<CarryRow> {
        val parents = rows.filter { it.kind == "SERIES" }.associate { row -> row.id to row.providerId.ifEmpty {
            legacyProviderId(vault.decrypt(row.legacyUrl), row.kind)
        } }
        return rows.map { row ->
            if (row.providerId.isNotEmpty() || row.legacyUrl.isEmpty()) row.copy(legacyUrl = "")
            else row.copy(providerId = legacyProviderId(vault.decrypt(row.legacyUrl), row.kind,
                parents[row.parent].orEmpty()), legacyUrl = "")
        }
    }

    // Only decoding belongs in this block: database and preference failures must propagate.
    private inline fun <T> decodeRemovalSnapshot(block: () -> T): T? = try {
        block()
    } catch (error: GeneralSecurityException) {
        removalSnapshotWarning(error)
        null
    } catch (error: IllegalArgumentException) {
        removalSnapshotWarning(error)
        null
    } catch (error: JSONException) {
        removalSnapshotWarning(error)
        null
    }

    private fun removalSnapshotWarning(error: Exception) {
        android.util.Log.w("LumenaraRepository", wp16Diagnostic(error, Wp16Stage.CARRY))
    }

    // Also used for a cancelled add after its catalog committed: re-save recovered state before cleanup.
    suspend fun removeUnlocked(id: String) {
        val source = library.source(id) ?: return
        val spec = decodeRemovalSnapshot { SourceSpec.decode(vault.decrypt(source.endpoint)) } ?: run {
            library.deleteSource(id)
            return
        }
        val now = System.currentTimeMillis()
        val snapshots = preferences.orphanSnapshots(now)
        val storedRows = library.carryRows(id, legacy = true)
        val rows = decodeRemovalSnapshot {
            if (spec.user.isNotEmpty()) legacyCarryRows(storedRows) else storedRows.map { row ->
                row.copy(urlHash = row.legacyUrl.takeIf { it.isNotEmpty() }
                    ?.let { contentKey("orphan-url", vault.decrypt(it)) }.orEmpty(), legacyUrl = "")
            }
        } ?: run {
            library.deleteSource(id)
            return
        }
        val excluded = context.getSharedPreferences("watch_next_state", Context.MODE_PRIVATE)
            .getStringSet("excluded", emptySet()).orEmpty().toSet()
        val provider = orphanProvider(spec)
        val ids = rows.map { it.id }.toSet()
        val redirects = library.redirects().filter { it.newId in ids }
        val snapshot = provider?.let { orphanHistory(id, it, now, rows, library.backupMarks(),
            library.carryMembers(), preferences.carryReferences(rows) + excluded + redirects.map { it.newId })
            .copy(redirects = redirects) }
        restoreTransaction.run {
            preferences.saveOrphanSnapshots(snapshots + listOfNotNull(snapshot?.takeIf { it.pendingIds().isNotEmpty() }))
            library.deleteSource(id)
        }
    }

}
