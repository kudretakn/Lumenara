package dev.lumenara.data

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Disposable, authenticated cache files. Production wraps each file key with the existing Vault
 * data key; neither the Vault alias nor its wrapped data-key preference is changed. Independent
 * 64 KiB GCM records avoid providers buffering a whole catalogue until the final GCM tag.
 * Record index/length and an authenticated EOF prevent reordering and silent truncation.
 */
internal class EncryptedSpool(
    private val sealKey: (String) -> String,
    private val openKey: (String) -> String,
) {
    constructor(vault: Vault) : this(vault::encrypt, vault::decrypt)

    fun create(cache: File): Blob = wp16Operation(Wp16Stage.SPOOL_WRITE) {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val wrapped = sealKey(Base64.getEncoder().encodeToString(key.encoded))
        Blob(File.createTempFile(PREFIX, ".spool", cache), key, wrapped)
    }

    inner class Blob internal constructor(private val file: File, private val key: SecretKey,
        private val wrapped: String) : Closeable {
        private var written = false
        private var closed = false

        fun write(produce: (OutputStream) -> Unit) = wp16Operation(Wp16Stage.SPOOL_WRITE) {
            check(!written && !closed)
            try {
                file.outputStream().buffered().use { raw ->
                    val output = DataOutputStream(raw)
                    val nonce = ByteArray(8).also { random.nextBytes(it) }
                    output.writeInt(MAGIC)
                    output.writeUTF(wrapped)
                    output.write(nonce)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val buffer = ByteArray(CHUNK_BYTES)
                    var count = 0
                    var sequence = 0
                    fun record(size: Int) {
                        check(sequence >= 0)
                        cipher.init(Cipher.ENCRYPT_MODE, key, parameters(nonce, sequence))
                        cipher.updateAAD(aad(sequence++, size))
                        output.writeInt(size)
                        output.write(cipher.doFinal(buffer, 0, size))
                    }
                    val stream = object : OutputStream() {
                        override fun write(value: Int) {
                            buffer[count++] = value.toByte()
                            if (count == buffer.size) { record(count); count = 0 }
                        }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                            var at = offset
                            var remaining = length
                            while (remaining > 0) {
                                val size = minOf(remaining, buffer.size - count)
                                bytes.copyInto(buffer, count, at, at + size)
                                count += size; at += size; remaining -= size
                                if (count == buffer.size) { record(count); count = 0 }
                            }
                        }
                        // The producer may close its writer; the enclosing scope owns the file.
                        override fun close() = Unit
                    }
                    try {
                        produce(stream)
                        if (count > 0) record(count)
                        record(0)
                    } finally { buffer.fill(0) }
                }
                written = true
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun input(): InputStream = wp16Operation(Wp16Stage.SPOOL_READ) {
            check(written && !closed)
            val input = DataInputStream(file.inputStream().buffered())
            try {
                check(input.readInt() == MAGIC)
                val header = input.readUTF()
                check(header.length <= 1024)
                val bytes = Base64.getDecoder().decode(openKey(header))
                val readKey = try {
                    check(bytes.size == 32)
                    SecretKeySpec(bytes, "AES")
                } finally { bytes.fill(0) }
                val nonce = ByteArray(8).also { input.readFully(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                object : InputStream() {
                    private var buffer = ByteArray(0)
                    private var offset = 0
                    private var sequence = 0
                    private var eof = false
                    private var inputClosed = false
                    private fun availableRecord(): Boolean = wp16Operation(Wp16Stage.SPOOL_READ) {
                        check(!inputClosed)
                        if (offset < buffer.size) return@wp16Operation true
                        if (eof) return@wp16Operation false
                        buffer.fill(0)
                        val size = input.readInt()
                        check(size in 0..CHUNK_BYTES && sequence >= 0)
                        val encrypted = ByteArray(size + TAG_BYTES).also { input.readFully(it) }
                        cipher.init(Cipher.DECRYPT_MODE, readKey, parameters(nonce, sequence))
                        cipher.updateAAD(aad(sequence++, size))
                        buffer = cipher.doFinal(encrypted)
                        offset = 0
                        if (size == 0) {
                            check(input.read() == -1)
                            eof = true
                            false
                        } else true
                    }
                    override fun read(): Int = if (availableRecord()) buffer[offset++].toInt() and 255 else -1
                    override fun read(bytes: ByteArray, at: Int, length: Int): Int {
                        require(at >= 0 && length >= 0 && at <= bytes.size - length)
                        if (length == 0) return 0
                        if (!availableRecord()) return -1
                        val count = minOf(length, buffer.size - offset)
                        buffer.copyInto(bytes, at, offset, offset + count)
                        offset += count
                        return count
                    }
                    override fun close() {
                        if (!inputClosed) {
                            inputClosed = true
                            buffer.fill(0)
                            input.close()
                        }
                    }
                }
            } catch (error: Throwable) {
                input.close()
                throw error
            }
        }

        override fun close() { closed = true; file.delete() }
    }

    companion object {
        private const val PREFIX = "wp16-spool-"
        private const val MAGIC = 0x4c535031 // LSP1
        internal const val CHUNK_BYTES = 64 * 1024
        private const val TAG_BYTES = 16
        private val random = SecureRandom()
        private fun parameters(prefix: ByteArray, sequence: Int) = GCMParameterSpec(128,
            ByteBuffer.allocate(12).put(prefix).putInt(sequence).array())
        private fun aad(sequence: Int, size: Int) =
            ByteBuffer.allocate(12).putInt(MAGIC).putInt(sequence).putInt(size).array()

        /** Standalone transport/stage callers have no Context. Keys live only for this session.
         * Repository always supplies the Vault-backed instance, including restore and XMLTV. */
        fun ephemeral(): EncryptedSpool {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return EncryptedSpool(sealKey = { value ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
            }, openKey = { value ->
                val bytes = Base64.getDecoder().decode(value)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
                cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
            })
        }

        /** Before Repository publication, with no active imports. Never resume uncommitted files.
         * Also remove the old plaintext body/stage formats left by an interrupted upgrade. */
        fun cleanup(cache: File) = wp16Operation(Wp16Stage.SPOOL_CLEANUP) {
            checkNotNull(cache.listFiles()).filter { file ->
                (file.isFile && (file.name.startsWith(PREFIX) && file.name.endsWith(".spool") ||
                    file.name.startsWith("refresh-") && file.name.endsWith(".body"))) ||
                    file.name == "catalog-import" || file.name == "wp12-stages"
            }.forEach { check(it.deleteRecursively()) }
        }
    }
}
