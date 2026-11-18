package dev.lumenara.data

import dev.lumenara.catalog.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.io.IOException

internal fun subtitleCacheFileName(key: String, extension: String): String =
    "${contentKey("sub", key).replace(Regex("[^A-Za-z0-9._-]"), "_")}.$extension"

/** Only normalized UTF-8 subtitle files are persisted; API links and credentials are never cached. */
class SubtitleCache(private val directory: File) {
    /** Copy before the catalog commits; retain the old cache file if Room rolls back. */
    @Synchronized internal fun carryKeys(keys: Map<String, String>) {
        for ((old, new) in keys) for (type in SubtitleType.entries) {
            val source = File(directory, subtitleCacheFileName(old, type.extension))
            val target = File(directory, subtitleCacheFileName(new, type.extension))
            if (old == new || !source.isFile || target.isFile) continue
            val temporary = File.createTempFile("subtitle-carry-", ".tmp", directory)
            try {
                source.copyTo(temporary, overwrite = true)
                try { Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE) }
                catch (_: IOException) { Files.move(temporary.toPath(), target.toPath()) }
            } finally { temporary.delete() }
        }
    }

    @Synchronized fun find(key: String): File? = SubtitleType.entries.map { File(directory, subtitleCacheFileName(key, it.extension)) }
        .firstOrNull { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }
    @Synchronized fun save(key: String, bytes: ByteArray, name: String): File {
        val decoded = decodeSubtitle(bytes, name)
        directory.mkdirs()
        val target = File(directory, subtitleCacheFileName(key, decoded.type.extension))
        val temporary = File.createTempFile("subtitle-", ".tmp", directory)
        try {
            temporary.writeText(decoded.text, Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: IOException) {
                // Windows may report an existing destination as a generic
                // FileSystemException for ATOMIC_MOVE (rather than
                // AtomicMoveNotSupportedException). Retry with replacement;
                // the temp file is still in the same directory/filesystem.
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
            SubtitleType.entries.filter { it != decoded.type }.forEach {
                File(directory, subtitleCacheFileName(key, it.extension)).delete()
            }
        } finally { temporary.delete() }
        var size = 0L
        directory.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
            .forEachIndexed { index, file ->
                size += file.length()
                if (file != target && (index >= 50 || size > 64L * 1024 * 1024)) file.delete()
            }
        return target
    }
}

class SubtitleService(private val credentials: suspend () -> SubtitleCredentials, val cache: SubtitleCache) {
    constructor(prefs: Preferences, cache: SubtitleCache) : this({
        SubtitleCredentials(prefs.secret("opensubtitles_key").ifBlank { BuildConfig.OPENSUBTITLES_API_KEY }, prefs.secret("opensubtitles_user"),
            prefs.secret("opensubtitles_password"), prefs.secret("subdl_key"))
    }, cache)
    val hasBuiltInOpenKey: Boolean get() = BuildConfig.OPENSUBTITLES_API_KEY.isNotBlank()
    // Injectable without changing release defaults. Endpoint enforces HTTPS except loopback fixtures.
    var openEndpoint = SubtitleEndpoint("https://api.opensubtitles.com/api/v1/")
    var subdlEndpoint = SubtitleEndpoint("https://api.subdl.com/api/v1/", "https://dl.subdl.com/")
    private var open: OpenSubtitles? = null
    private var subdl: Subdl? = null
    suspend fun configured() = credentials().let { it.openKey.isNotBlank() || it.subdlKey.isNotBlank() }
    suspend fun search(query: SubtitleQuery, skipOpen: Boolean = false): SubtitleResults {
        val c = credentials()
        if (c.openKey.isBlank() && c.subdlKey.isBlank()) return SubtitleResults(emptyList(), "Ayarlar’dan OpenSubtitles veya Subdl API anahtarı ekleyin.")
        var message = if (skipOpen) "OpenSubtitles günlük sınırı doldu" else ""
        if (c.openKey.isNotBlank() && !skipOpen) {
            open = OpenSubtitles(c, openEndpoint)
            try {
                val result = checkNotNull(open).search(query)
                if (result.entries.isNotEmpty()) return result
            } catch (e: SubtitleFailure) {
                if (!e.quota) throw e
                message = "OpenSubtitles günlük sınırı doldu"
            }
        }
        if (c.subdlKey.isNotBlank()) {
            subdl = Subdl(c.subdlKey, subdlEndpoint)
            val response = checkNotNull(subdl).search(query)
            return response.copy(message = listOf(message, if (response.entries.isEmpty()) "Türkçe altyazı bulunamadı." else "")
                .filter { it.isNotBlank() }.joinToString(" · "))
        }
        return SubtitleResults(emptyList(), message.ifBlank { "Türkçe altyazı bulunamadı." })
    }
    fun download(key: String, result: SubtitleResult): Pair<File, Int?> {
        val (bytes, name, remaining) = if (result.source == "OpenSubtitles") {
            val (data, left) = checkNotNull(open).download(result)
            Triple(data, result.fileName, left)
        } else {
            val (data, name) = checkNotNull(subdl).download(result)
            Triple(data, name, null)
        }
        return cache.save(key, bytes, name) to remaining
    }
}
