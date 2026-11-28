package dev.lumenara.data

import android.content.Context
import androidx.room.withTransaction
import dev.lumenara.catalog.contentKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class GuideImporter(
    private val context: Context,
    private val db: LibraryDb,
    private val vault: Vault,
    private val preferences: Preferences,
    private val lock: Mutex,
    private val imports: SourceImports,
    private val spool: EncryptedSpool,
) {
    private val library = db.library()
    private val guide = db.guide()
    suspend fun addGuide(sourceId: String, url: String) =
        withContext(Dispatchers.IO) {
            wp16Operation(Wp16Stage.GUIDE) {
                lock.withLock {
                    val source = library.source(sourceId) ?: throw SourceFailure("Kaynak bulunamadı.")
                    val address = url.ifBlank {
                        val spec = SourceSpec.decode(vault.decrypt(source.endpoint))
                        if (spec.user.isEmpty()) throw SourceFailure("XMLTV adresini girin.")
                        Xtream(spec).guideAddress()
                    }
                    syncGuide(
                        source,
                        Feed(contentKey(sourceId, address), sourceId, vault.encrypt(address)),
                    )
                }
            }
        }

    suspend fun refreshGuides(sourceId: String, progress: ImportProgress? = null) =
        withContext(Dispatchers.IO) {
            imports.run(progress) {
                val source = library.source(sourceId) ?: return@run
                progress?.report("Rehber indiriliyor…")
                for (feed in guide.feeds(sourceId)) syncGuide(source, feed)
            }
        }

    suspend fun syncGuide(source: Source, feed: Feed): Unit = wp16Operation(Wp16Stage.GUIDE) {
        val key = "feed_${feed.id}"
        val job = currentCoroutineContext()
        Transport.download(vault.decrypt(feed.encryptedAddress), source.allowHttp, context.cacheDir,
            preferences.fetchState(key), limit = 256L * 1024 * 1024, checkActive = { job.ensureActive() },
            calls = job[ImportCalls], spool = spool).use { downloaded ->
            job.ensureActive()
            val now = System.currentTimeMillis()
            val changed = downloaded.importIfChanged {
                db.withTransaction {
                    downloaded.read { input, _ ->
                        val buffered = input.buffered()
                        buffered.mark(2)
                        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
                        buffered.reset()
                        val xml = if (gzip) bounded(java.util.zip.GZIPInputStream(buffered), 256L * 1024 * 1024)
                            else buffered
                        populateGuide(db, feed, xml, now) { job.ensureActive() }
                    }
                }
            }
            if (!changed) {
                // Window maintenance is independent of importing an unchanged feed.
                val (from, until) = guideWindow(now)
                if (guide.hasOutsideWindow(feed.id, from, until)) guide.pruneWindow(feed.id, from, until)
            }
            preferences.setFetchState(key, downloaded.state)
        }
    }

}
