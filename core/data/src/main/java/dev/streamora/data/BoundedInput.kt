package dev.streamora.data

import java.io.FilterInputStream
import java.io.InputStream

internal fun bounded(input: InputStream, limit: Long = 64L * 1024 * 1024): InputStream =
    object : FilterInputStream(input) {
        var total = 0L

        fun check(count: Int) {
            if (count > 0) total += count
            if (total > limit) throw SourceFailure("Kaynak boyut sınırını aştı.")
        }

        override fun read() = super.read().also { if (it >= 0) check(1) }

        override fun read(bytes: ByteArray, offset: Int, length: Int) =
            super.read(bytes, offset, length).also(::check)
    }
