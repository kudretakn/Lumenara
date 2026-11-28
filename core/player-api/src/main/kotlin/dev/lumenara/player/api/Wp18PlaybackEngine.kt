package dev.lumenara.player.api

/** Commands run on the engine's application thread; stop/release await all input cleanup. */
interface PlaybackEngine {
    val kind: EngineKind
    fun play()
    fun pause()
    fun seekTo(position: Long)
    fun snapshot(): EngineSnapshot
    suspend fun stop()
    suspend fun release()
}

enum class EngineKind { MEDIA3, VLC }
data class EngineSnapshot(val position: Long, val duration: Long, val playing: Boolean)

/** The app supplies source-aware transport; engines never depend on repository/storage code. */
fun interface PlaybackNetwork {
    fun client(allowHttp: Boolean): okhttp3.Call.Factory
}
