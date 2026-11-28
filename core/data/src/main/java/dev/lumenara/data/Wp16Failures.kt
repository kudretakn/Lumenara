package dev.lumenara.data

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException

/** Diagnostic fields are allowlisted; exception messages, URLs and causes never enter them. */
internal enum class Wp16Stage {
    RESOLVE, TRANSPORT, CATALOG_PARSE, IMPORT, GUIDE, BACKUP, CARRY,
    SPOOL_WRITE, SPOOL_READ, SPOOL_CLEANUP, BACKFILL,
}

internal class Wp16TransportFailure(val diagnostic: String) : IOException(diagnostic)

internal fun wp16Diagnostic(error: Throwable, stage: Wp16Stage): String =
    if (error is SourceFailure && error.diagnostic != "DATA_FAILED|IMPORT|SourceFailure") error.diagnostic
    else if (error is Wp16TransportFailure) error.diagnostic
    else "DATA_FAILED|${stage.name}|${error.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: "Exception"}"

internal fun wp16Failure(error: Exception, stage: Wp16Stage): Exception {
    if (error is CancellationException) throw error
    if (error is SourceFailure) {
        val diagnostic = wp16Diagnostic(error, stage)
        val safe = if (error.cause != null || error.suppressed.isNotEmpty()) SourceFailure(error.message.orEmpty()) else error
        return safe.also { it.diagnostic = diagnostic }
    }
    if (error is Wp16TransportFailure) return Wp16TransportFailure(error.diagnostic)
    if (error is BackupRecoveryException) return BackupRecoveryException(
        SourceFailure(wp16Diagnostic(error, stage))
    )
    if (error is InterruptedIOException) return SourceFailure(
        "Sunucu isteği zaman aşımına uğradı. Yeniden deneyin veya başka bir sunucu adresi kullanın."
    ).also { it.diagnostic = wp16Diagnostic(error, Wp16Stage.TRANSPORT) }
    // Do not retain a cause/suppressed exception: its message may contain provider credentials.
    val diagnostic = wp16Diagnostic(error, stage)
    if (stage == Wp16Stage.TRANSPORT && error is IOException) return Wp16TransportFailure(diagnostic)
    return SourceFailure(diagnostic).also { it.diagnostic = diagnostic }
}

internal inline fun <T> wp16Operation(stage: Wp16Stage, block: () -> T): T = try {
    block()
} catch (error: Exception) {
    throw wp16Failure(error, stage)
}
