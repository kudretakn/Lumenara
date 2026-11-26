package dev.lumenara.data

/** UI lifecycle signals shared with WorkManager, without retaining an Activity or a player. */
object RefreshGate {
    @Volatile var foreground: Boolean = false
        set(value) { field = value; backgroundWork.startup.foreground(value) }
    @Volatile var playing: Boolean = false
        set(value) { field = value; backgroundWork.startup.playing(value) }

    internal fun defer(now: Long, deadline: Long): Boolean =
        (foreground || playing) && now < deadline

    internal val busy: Boolean get() = foreground || playing
}
