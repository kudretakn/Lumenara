package dev.lumenara.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import dev.lumenara.catalog.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SourceSpec(
    val address: String,
    val allowHttp: Boolean,
    val user: String = "",
    val password: String = "",
) {
    fun encode() =
        JSONObject()
            .put("address", address)
            .put("http", allowHttp)
            .put("user", user)
            .put("pass", password)
            .toString()

    companion object {
        fun decode(value: String) =
            JSONObject(value).let {
                SourceSpec(
                    it.getString("address"),
                    it.getBoolean("http"),
                    it.getString("user"),
                    it.getString("pass"),
                )
            }
    }
}

class Repository private constructor(private val context: Context) {
    private val db = Room.databaseBuilder(context, LibraryDb::class.java, "lumenara.db").build()
    val library = db.library()
    val guide = db.guide()
    private val vault = Vault(context)
    private val metadata = Metadata(context, vault)

    suspend fun details(item: Item) =
        metadata.details(item).also {
            if (it.collection.isNotBlank())
                db.withTransaction {
                    if (library.exists(item.id)) library.collection(Member(item.id, it.collection))
                }
        }

    suspend fun configureTmdb(token: String) =
        withContext(Dispatchers.IO) { metadata.configure(token) }

    fun tmdbConfigured() = metadata.configured()

    private val lock = Mutex()

    companion object {
        @Volatile private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance
                ?: synchronized(this) {
                    instance ?: Repository(context.applicationContext).also { instance = it }
                }
    }

    suspend fun add(name: String, spec: SourceSpec) =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { "Kaynak adını girin." }
            lock.withLock {
                val source =
                    Source(
                        UUID.randomUUID().toString(),
                        name.trim(),
                        vault.encrypt(spec.encode()),
                        spec.allowHttp,
                        0,
                        0,
                    )
                import(source, spec)
            }
        }

    suspend fun refresh(id: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val source = library.source(id) ?: return@withLock
                import(source, SourceSpec.decode(vault.decrypt(source.endpoint)))
            }
        }

    suspend fun remove(id: String) = lock.withLock { library.deleteSource(id) }

    suspend fun addGuide(sourceId: String, url: String) =
        withContext(Dispatchers.IO) {
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

    suspend fun refreshGuides(sourceId: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val source = library.source(sourceId) ?: return@withLock
                for (feed in guide.feeds(sourceId)) syncGuide(source, feed)
            }
        }

    private suspend fun syncGuide(source: Source, feed: Feed) = db.withTransaction {
        Transport.read(vault.decrypt(feed.encryptedAddress), source.allowHttp) { input, _ ->
            val buffered = input.buffered()
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val xml =
                if (gzip) bounded(java.util.zip.GZIPInputStream(buffered), 256L * 1024 * 1024)
                else buffered
            populateGuide(db, feed, xml)
        }
    }

    private suspend fun import(source: Source, spec: SourceSpec) {
        val token = maxOf(System.currentTimeMillis(), source.refreshed + 1)
        val staged = CatalogStage(context.cacheDir, source.id, token)
        var providerLimit = 0
        try {
            staged.write { append ->
                fun accept(row: Listing) = append(item(source.id, token, row))
                when {
                    spec.user.isNotEmpty() -> providerLimit = Xtream(spec).read(::accept)
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
                        Transport.read(spec.address, spec.allowHttp) { input, finalUrl ->
                            Playlist.read(
                                input.bufferedReader(),
                                URI(finalUrl),
                                spec.allowHttp,
                                ::accept,
                            )
                        }
                }
            }
            if (providerLimit > 0)
                Preferences(context).set("provider_${source.id}", providerLimit.toString())
            db.withTransaction {
                library.save(source)
                staged.read(library::writeBatch)
                library.prune(source.id, token)
                library.orphans(source.id)
                library.save(source.copy(refreshed = token, count = library.count(source.id)))
            }
        } finally {
            staged.close()
        }
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
            searchable("${entry.title} ${entry.group} ${entry.tvgId}"),
            token,
            entry.logo.takeIf { it.startsWith("https://") }?.let(vault::encrypt).orEmpty(),
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
        )

    suspend fun replay(channel: Item, slot: Slot): Item =
        withContext(Dispatchers.IO) {
            val source = library.source(channel.source) ?: throw SourceFailure("Kaynak bulunamadı.")
            val address =
                Catchup.address(
                    vault.decrypt(channel.archive),
                    channel.archiveDays,
                    slot.start,
                    slot.end,
                    System.currentTimeMillis(),
                    channel.archiveZone,
                    source.allowHttp,
                ) ?: throw SourceFailure("Program sağlayıcının arşiv aralığında değil.")
            channel.copy(
                title = "${channel.title} · ${slot.title}",
                encryptedUrl = vault.encrypt(address),
                kind = "REPLAY",
            )
        }

    suspend fun expand(series: Item): List<Item> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val source = library.source(series.source) ?: return@withLock emptyList()
                val spec = SourceSpec.decode(vault.decrypt(source.endpoint))
                val token = System.currentTimeMillis()
                val entries =
                    Xtream(spec)
                        .episodes(vault.decrypt(series.encryptedUrl), series.title)
                        .distinctBy { it.url }
                        .map {
                            item(source.id, token, it).copy(parent = series.id)
                        }
                db.withTransaction {
                    library.save(entries)
                    library.pruneEpisodes(series.id, token)
                }
                entries
            }
        }

    suspend fun address(item: Item) =
        withContext(Dispatchers.IO) { vault.decrypt(item.encryptedUrl) }

    suspend fun poster(item: Item) =
        withContext(Dispatchers.IO) { item.logo.takeIf(String::isNotEmpty)?.let(vault::decrypt) }

    suspend fun backdrop(item: Item) =
        withContext(Dispatchers.IO) {
            item.backdrop.takeIf(String::isNotEmpty)?.let(vault::decrypt)
        }

    suspend fun updateMark(id: String, transform: (Mark) -> Mark) = db.withTransaction {
        if (library.exists(id)) library.save(transform(library.mark(id) ?: Mark(id)))
    }

    suspend fun schedule(hours: Long, checkNow: Boolean = false) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            PeriodicWorkRequestBuilder<RefreshWorker>(hours, TimeUnit.HOURS)
                .setInitialDelay(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork("iptv", ExistingPeriodicWorkPolicy.UPDATE, request)
        if (
            checkNow &&
                library.snapshot().any {
                    System.currentTimeMillis() - it.refreshed >= TimeUnit.HOURS.toMillis(hours)
                }
        ) {
            manager.enqueueUniqueWork(
                "startup-refresh",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf("staleHours" to hours))
                    .build(),
            )
        }
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repository.get(applicationContext)
        var failed = false
        val prefs = Preferences(applicationContext)
        for (source in repo.library.snapshot()) {
            val threshold = inputData.getLong("staleHours", 0)
            if (
                threshold > 0 &&
                    runAttemptCount == 0 &&
                    System.currentTimeMillis() - source.refreshed <
                        TimeUnit.HOURS.toMillis(threshold)
            )
                continue
            try {
                repo.refresh(source.id)
                repo.refreshGuides(source.id)
                prefs.set("sync_${source.id}", "Güncel")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
                prefs.set("sync_${source.id}", "Yenileme başarısız")
            }
        }
        return when {
            !failed -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }
}
