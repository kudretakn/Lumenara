package dev.lumenara.player

/** Renderer input events can arrive after a zap. Advertised track groups are not selected formats. */
class Wp18InputFormat(private val mediaId: String) {
    var frameRate: Float? = null
        private set

    fun accept(actualMediaId: String?, fps: Float): Boolean {
        if (actualMediaId != mediaId) return false
        frameRate = fps.takeIf { it.isFinite() && it > 0f }
        return true
    }
}
