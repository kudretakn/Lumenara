package dev.lumenara.data

/** Memory hits need no disk I/O. Validate retention only after Coil has missed memory. */
suspend fun <T> retainArtworkResult(
    result: T,
    fromDisk: Boolean,
    expired: suspend () -> Boolean,
    reload: suspend () -> T,
): T = if (fromDisk && expired()) reload() else result
