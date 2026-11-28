package dev.lumenara.vlc

/** Small, non-consuming signature probe. Manifests and unknown data stay with Media3. */
internal object Wp09VlcSniff {
    const val MAX_BYTES = 188 * 3

    fun demux(prefix: ByteArray): String? {
        if (prefix.size >= 12 && prefix.copyOfRange(4, 8).contentEquals(byteArrayOf(102, 116, 121, 112))) {
            return "mp4" // ISO BMFF ftyp, including extensionless provider endpoints.
        }
        for (offset in 0 until minOf(188, prefix.size)) {
            if (offset + 376 < prefix.size && prefix[offset] == 0x47.toByte() &&
                prefix[offset + 188] == 0x47.toByte() && prefix[offset + 376] == 0x47.toByte()) return "ts"
        }
        return null
    }
}

internal data class Wp09ContentRange(val first: Long, val last: Long, val total: Long) {
    companion object {
        fun parse(value: String?): Wp09ContentRange? {
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value ?: return null) ?: return null
            val (first, last, total) = match.destructured
            val range = Wp09ContentRange(first.toLongOrNull() ?: return null,
                last.toLongOrNull() ?: return null, total.toLongOrNull() ?: return null)
            return range.takeIf { it.first <= it.last && it.last < it.total }
        }
    }
}
