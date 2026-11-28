package dev.lumenara.player

/** Bounded playback window, independent of decoders and Android views. */
internal class SubtitleTimeline<T>(private val merge: Boolean) {
    private data class Entry<T>(val end: Long, val cues: List<T>)
    private val entries = java.util.TreeMap<Long, MutableList<Entry<T>>>()

    fun add(start: Long, end: Long, cues: List<T>) {
        val value = Entry(end, cues)
        if (merge) entries.getOrPut(start) { mutableListOf() }.add(value)
        else entries[start] = mutableListOf(value)
    }

    fun at(time: Long): List<T> = if (merge) buildList {
        for (group in entries.headMap(time, true).values)
            for (entry in group) if (time < entry.end) addAll(entry.cues)
    } else entries.floorEntry(time)?.value?.lastOrNull()?.takeIf { time < it.end }?.cues.orEmpty()

    fun prune(before: Long) {
        val floor = entries.floorKey(before) ?: return
        val iterator = entries.headMap(floor, false).entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!merge || entry.value.all { it.end < before }) iterator.remove()
        }
    }
}
