package dev.lumenara.data

/** Call under Repository's import/expansion lock; Room remains the episode cache. */
internal class SeriesExpansionCache(
    private val readStamp: suspend (String) -> Long,
    private val writeStamp: suspend (String, Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stamps = object : LinkedHashMap<String, Long>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 128
    }

    suspend fun <T> get(id: String, cached: suspend () -> T, fetch: suspend () -> T): T {
        val stamp = stamps[id] ?: readStamp(id).also { stamps[id] = it }
        val age = clock() - stamp
        if (stamp > 0 && age >= 0 && age < SIX_HOURS_MS) return cached()
        val result = fetch()
        val updated = clock()
        // Failed/cancelled fetches or transactions must remain retryable.
        writeStamp(id, updated)
        stamps[id] = updated
        return result
    }

    internal companion object {
        const val SIX_HOURS_MS = 6 * 60 * 60 * 1000L
    }
}
