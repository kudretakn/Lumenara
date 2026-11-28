package dev.lumenara.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class Wp16FailuresTest {
    private val raw = "https://private.invalid/api?username=private&password=secret"

    @Test fun diagnosticsNeverContainMessagesCausesSuppressedErrorsOrUrls() {
        val error = IOException(raw, IllegalArgumentException(raw)).apply { addSuppressed(IOException(raw)) }
        assertEquals("DATA_FAILED|GUIDE|IOException", wp16Diagnostic(error, Wp16Stage.GUIDE))
        val safe = wp16Failure(error, Wp16Stage.GUIDE)
        assertEquals("DATA_FAILED|GUIDE|IOException", safe.message)
        assertNull(safe.cause)
        assertTrue(safe.suppressed.isEmpty())
    }

    @Test fun recoveryRequiredTypeSurvivesWithoutTheUnsafeCauseChain() {
        val safe = wp16Failure(BackupRecoveryException(IOException(raw)), Wp16Stage.BACKUP)
        assertTrue(safe is BackupRecoveryException)
        assertEquals("DATA_FAILED|BACKUP|BackupRecoveryException", safe.cause!!.message)
        assertNull(safe.cause!!.cause)
    }

    @Test fun knownFailuresCannotRetainUnsafeSuppressedCloseErrors() {
        val original = SourceFailure("Kaynak bulunamadı.").apply { addSuppressed(IOException(raw)) }
        val safe = wp16Failure(original, Wp16Stage.IMPORT)
        assertEquals("Kaynak bulunamadı.", safe.message)
        assertTrue(safe.suppressed.isEmpty())
        assertNull(safe.cause)
    }

    @Test fun cancellationIdentityAndExistingSafeUserMessagesArePreserved() {
        val cancellation = CancellationException(raw)
        assertSame(cancellation, runCatching { wp16Failure(cancellation, Wp16Stage.IMPORT) }.exceptionOrNull())
        val safeUserError = SourceFailure("Kaynak bulunamadı.")
        assertSame(safeUserError, wp16Failure(safeUserError, Wp16Stage.GUIDE))
        assertEquals("Kaynak bulunamadı.", safeUserError.message)
        assertEquals("DATA_FAILED|GUIDE|SourceFailure", wp16Diagnostic(safeUserError, Wp16Stage.GUIDE))
    }
}
