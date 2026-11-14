package dev.lumenara.data

import dev.lumenara.catalog.*
import java.io.File

/** Only normalized UTF-8 subtitle files are persisted; API links and credentials are never cached. */
class SubtitleCache(private val directory: File) {
    @Synchronized fun find(key: String): File? = SubtitleType.entries.map { File(directory, "${contentKey("sub", key)}.${it.extension}") }
        .firstOrNull { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }
    @Synchronized fun save(key: String, bytes: ByteArray, name: String): File {
        val decoded = decodeSubtitle(bytes, name)
        directory.mkdirs()
        val target = File(directory, "${contentKey("sub", key)}.${decoded.type.extension}")
        val temporary = File.createTempFile("subtitle-", ".tmp", directory)
        try {
            temporary.writeText(decoded.text, Charsets.UTF_8)
            check(temporary.renameTo(target))
            SubtitleType.entries.filter { it != decoded.type }.forEach {
                File(directory, "${contentKey("sub", key)}.${it.extension}").delete()
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
        SubtitleCredentials(prefs.secret("opensubtitles_key"), prefs.secret("opensubtitles_user"),
            prefs.secret("opensubtitles_password"), prefs.secret("subdl_key"))
    }, cache)
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
