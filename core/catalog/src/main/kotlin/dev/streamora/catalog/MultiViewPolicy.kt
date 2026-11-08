package dev.streamora.catalog

data class MultiSelection<T>(val entries: List<T>, val message: String = "")

fun <T> multiSelection(
    count: Int,
    slots: Int,
    items: List<T>,
    limits: Map<String, Int>,
    source: (T) -> String,
): MultiSelection<T> {
    if (count !in listOf(2, 4) || slots < count)
        return MultiSelection(emptyList(), "Cihaz yeterli donanımsal görüntü çözücü bildirmiyor.")
    val selected = mutableListOf<T>()
    val used = mutableMapOf<String, Int>()
    for (item in items) {
        val id = source(item)
        val current = used.getOrDefault(id, 0)
        if (current >= limits.getOrDefault(id, 1)) continue
        selected += item
        used[id] = current + 1
        if (selected.size == count) return MultiSelection(selected)
    }
    return MultiSelection(emptyList(), "Kanal sayısı veya kaynak bağlantı sınırı yetersiz.")
}
