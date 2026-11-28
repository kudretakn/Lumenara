package dev.lumenara.player

/** Selected input format, never an arbitrary advertised ABR variant. Unknown load is one slot. */
data class Wp18VideoLoad(val mime: String?, val width: Int, val height: Int, val frameRate: Float)

fun wp18DecoderWindow(reported: Int, load: Wp18VideoLoad?): Int {
    if (load == null || load.mime != "video/avc" || load.width <= 0 || load.height <= 0 ||
        !load.frameRate.isFinite() || load.frameRate <= 0 || load.frameRate > 30f ||
        load.width > 1920 || load.height > 1080) return 1
    // Vendor maxSupportedInstances is an upper bound, not a guarantee under TV memory pressure.
    return reported.coerceIn(1, 2)
}
