package dev.lumenara.vlc

/** Standalone JVM test entry point; requires neither native VLC nor a test dependency. */
object VlcRuntimeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        var creations = 0
        val runtime = VlcRuntime {
            creations++
            Any() // Stand-in for the native LibVLC instance.
        }
        check(creations == 0) { "LibVLC must be created only on first playback" }

        val firstPlaybackLibrary = runtime.get()
        val secondPlaybackLibrary = runtime.get()

        check(firstPlaybackLibrary === secondPlaybackLibrary) { "Playbacks must share LibVLC" }
        check(creations == 1) { "Two playbacks must create LibVLC exactly once" }
    }
}
