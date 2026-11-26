package dev.lumenara.tv

import dev.lumenara.data.Item
import dev.lumenara.data.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Main-thread, session-only bounded addresses; warming never opens another IPTV stream. */
internal class LiveUrlCache(private val model: LibraryModel) {
    private val urls = linkedMapOf<String, String>()

    suspend fun address(item: Item): String {
        val key = item.encryptedUrl
        urls.remove(key)?.let { urls[key] = it; return it }
        val url = model.repo.address(item)
        urls[key] = url
        while (urls.size > 3) urls.remove(urls.keys.first())
        return url
    }

    suspend fun warm(item: Item) {
        try {
            val url = address(item).toHttpUrl()
            val source = model.repo.library.source(item.source) ?: return
            if (!url.isHttps && !source.allowHttp) return
            withContext(Dispatchers.IO) { Transport.client(source.allowHttp).dns.lookup(url.host) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Speculation must never interrupt the playing channel or arm reconnect.
        }
    }
}
