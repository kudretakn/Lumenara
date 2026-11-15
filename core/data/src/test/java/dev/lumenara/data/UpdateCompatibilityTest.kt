package dev.lumenara.data

import org.junit.Assert.*
import org.junit.Test

class UpdateCompatibilityTest {
    @Test fun acceptsNewerApkWithMatchingSigners() {
        assertNull(updateMismatch(true, 7L, 6L, setOf("certificate"), setOf("certificate")))
    }

    @Test fun rejectsDifferentPackageEvenWhenSignersAreUnknown() {
        assertEquals(
            UpdateMismatch.PACKAGE,
            updateMismatch(false, 7L, 6L, emptySet<String>(), emptySet()),
        )
        assertEquals("APK paket adı farklı.", UpdateMismatch.PACKAGE.message)
    }

    @Test fun rejectsEqualOrOlderVersionEvenWhenSignersAreUnknown() {
        for (archiveCode in listOf(5L, 6L)) {
            assertEquals(
                UpdateMismatch.VERSION_CODE,
                updateMismatch(true, archiveCode, 6L, emptySet<String>(), emptySet()),
            )
        }
        assertEquals(
            "APK sürüm kodu eski veya yüklü sürümle aynı.",
            UpdateMismatch.VERSION_CODE.message,
        )
    }

    @Test fun rejectsKnownSignerMismatch() {
        assertEquals(
            UpdateMismatch.SIGNATURE,
            updateMismatch(true, 7L, 6L, setOf("other"), setOf("certificate")),
        )
        assertEquals("APK imzası farklı.", UpdateMismatch.SIGNATURE.message)
    }

    @Test fun defersUnknownArchiveSignersToInstaller() {
        assertNull(updateMismatch(true, 7L, 6L, emptySet(), setOf("certificate")))
    }

    @Test fun defersUnknownInstalledSignersToInstaller() {
        assertNull(updateMismatch(true, 7L, 6L, setOf("certificate"), emptySet()))
    }

    @Test fun defersBothUnknownSignerSetsToInstaller() {
        assertNull(updateMismatch(true, 7L, 6L, emptySet<String>(), emptySet()))
    }

    @Test fun comparesEntireSignerSetsRegardlessOfOrder() {
        assertNull(updateMismatch(true, 7L, 6L, setOf("a", "b"), setOf("b", "a")))
        assertEquals(
            UpdateMismatch.SIGNATURE,
            updateMismatch(true, 7L, 6L, setOf("a", "b"), setOf("a")),
        )
    }

    @Test fun preservesLongVersionCodes() {
        val installedCode = Int.MAX_VALUE.toLong() + 1
        assertNull(updateMismatch(true, installedCode + 1, installedCode, setOf("a"), setOf("a")))
        assertEquals(
            UpdateMismatch.VERSION_CODE,
            updateMismatch(true, installedCode - 1, installedCode, setOf("a"), setOf("a")),
        )
    }
}
