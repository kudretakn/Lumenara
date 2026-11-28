package dev.lumenara.data

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Wp16EncryptedSpoolTest {
    @get:Rule val cache = TemporaryFolder()
    private val marker = "https://private.invalid/live/wp16-user/wp16-password/42.ts"

    @Test fun existingVaultFunctionsWrapOnlyTheFileKeyAndAllBytesRoundTrip() {
        // The injected functions have the exact Vault.encrypt/decrypt contract; no Android stubs.
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var sealed = 0
        var opened = 0
        val spool = EncryptedSpool(sealKey = { text ->
            sealed++
            assertEquals(32, Base64.getDecoder().decode(text).size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapping)
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()))
        }, openKey = { text ->
            opened++
            val bytes = Base64.getDecoder().decode(text)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, bytes, 0, 12))
            cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
        })
        for (size in listOf(0, 1, 65535, 65536, 65537, 3 * 65536 + 19)) {
            val bytes = ByteArray(size) { marker[it % marker.length].code.toByte() }
            spool.create(cache.root).use { blob ->
                blob.write { output ->
                    bytes.asList().chunked(8001).forEach { output.write(it.toByteArray()) }
                }
                val disk = cache.root.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(disk.contains(marker))
                assertArrayEquals(bytes, blob.input().use { it.readBytes() })
                // Reopening is required by staged import readers.
                assertArrayEquals(bytes, blob.input().use { it.readBytes() })
            }
        }
        assertEquals(6, sealed)
        assertEquals(12, opened)
        assertTrue(cache.root.listFiles()!!.isEmpty())
    }

    @Test fun corruptionTruncationMissingEofAndReorderingAreRejected() {
        EncryptedSpool.ephemeral().create(cache.root).use { blob ->
            blob.write { it.write(marker.repeat(4000).toByteArray()) }
            val file = cache.root.listFiles()!!.single()
            val original = file.readBytes()
            val input = DataInputStream(ByteArrayInputStream(original))
            input.readInt(); input.readUTF(); input.skipBytes(8)
            val headerSize = original.size - input.available()
            val recordSize = 4 + EncryptedSpool.CHUNK_BYTES + 16
            val reversed = original.copyOf().also {
                original.copyInto(it, headerSize, headerSize + recordSize, headerSize + recordSize * 2)
                original.copyInto(it, headerSize + recordSize, headerSize, headerSize + recordSize)
            }
            val corruptions = listOf(
                original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
                original.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() },
                original.copyOf().also {
                    it[headerSize] = 127; it[headerSize + 1] = -1; it[headerSize + 2] = -1; it[headerSize + 3] = -1
                },
                original.copyOf().also { it[headerSize + 10] = (it[headerSize + 10].toInt() xor 1).toByte() },
                original.copyOf(original.size - 1),
                original.copyOf(original.size - 20), // Remove the complete authenticated EOF.
                original.copyOf(headerSize + 11),
                original + byteArrayOf(1),
                reversed,
            )
            for (bytes in corruptions) {
                file.writeBytes(bytes)
                val error = runCatching { blob.input().use { it.readBytes() } }.exceptionOrNull()
                assertTrue(error is SourceFailure)
                assertTrue(error!!.message!!.matches(Regex("DATA_FAILED\\|SPOOL_READ\\|[A-Za-z0-9_]+")))
                assertNull(error.cause)
                assertTrue(error.suppressed.isEmpty())
            }
        }
    }

    @Test fun cancellationAndProducerFailureDeleteThePartialFile() {
        for (failure in listOf(CancellationException(marker), IOException(marker))) {
            val blob = EncryptedSpool.ephemeral().create(cache.root)
            val error = runCatching {
                blob.write { output -> output.write(ByteArray(100_000)); throw failure }
            }.exceptionOrNull()
            if (failure is CancellationException) assertSame(failure, error)
            else {
                assertEquals("DATA_FAILED|SPOOL_WRITE|IOException", error!!.message)
                assertNull(error.cause)
            }
            assertTrue(cache.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun nextStartupDeletesAnActualHalfWriteAndLegacyPlaintextWithoutTouchingOtherCaches() {
        val partial = File(cache.root, "wp16-spool-killed.spool")
        EncryptedSpool.ephemeral().create(cache.root).use { blob ->
            blob.write { output ->
                output.write(marker.repeat(4000).toByteArray())
                // Snapshot the on-disk bytes before the writer has emitted its EOF record.
                val active = cache.root.listFiles()!!.single()
                assertTrue(active.length() > 65536)
                active.copyTo(partial)
            }
        }
        val legacy = File(cache.root, "refresh-old.body").apply { writeText(marker) }
        File(cache.root, "catalog-import").apply { mkdir() }.resolve("pending.ndjson").writeText(marker)
        File(cache.root, "wp12-stages/catalog-old").apply { mkdirs() }.resolve("partial").writeText(marker)
        val unrelated = File(cache.root, "subtitle-cache").apply { writeText("keep") }
        EncryptedSpool.cleanup(cache.root)
        EncryptedSpool.cleanup(cache.root) // Idempotent on every process start.
        assertFalse(partial.exists())
        assertFalse(legacy.exists())
        assertEquals(listOf(unrelated), cache.root.listFiles()!!.toList())
    }

    @Test fun singleByteAndZeroLengthReadsKeepInputStreamContract() {
        EncryptedSpool.ephemeral().create(cache.root).use { blob ->
            blob.write { it.write(byteArrayOf(0, -1, 42)) }
            blob.input().use { input ->
                assertEquals(0, input.read(ByteArray(0)))
                assertEquals(0, input.read())
                assertEquals(255, input.read())
                assertEquals(42, input.read())
                assertEquals(-1, input.read())
                assertEquals(-1, input.read())
                assertEquals(0, input.read(ByteArray(0)))
            }
        }
    }
}
