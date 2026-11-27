package dev.lumenara.tv

internal const val PAGE_WINDOW_RADIUS = 2

/** Pure window policy, shared by every lazy catalog and directly exercised by JVM tests. */
internal class PagingWindow<T>(val pageSize: Int, val pages: MutableMap<Int, List<T>> = linkedMapOf()) {
    init { require(pageSize > 0) }

    fun visibleCenter(index: Int, focusedIndex: Int?): Int {
        val page = index.coerceAtLeast(0) / pageSize
        val focused = focusedIndex?.coerceAtLeast(0)?.div(pageSize)
        return if (focused != null && kotlin.math.abs(page - focused) <= PAGE_WINDOW_RADIUS) focused else page
    }

    fun range(center: Int, count: Int, totalKnown: Boolean): IntRange {
        val page = center.coerceAtLeast(0)
        if (!totalKnown) return page..page
        val last = (count - 1).coerceAtLeast(0) / pageSize
        val clamped = page.coerceAtMost(last)
        return (clamped - PAGE_WINDOW_RADIUS).coerceAtLeast(0)..
            (clamped + PAGE_WINDOW_RADIUS).coerceAtMost(last)
    }

    fun install(range: IntRange, rows: List<T>) {
        require(range.last - range.first < 2 * PAGE_WINDOW_RADIUS + 1)
        retain(range)
        range.forEach { page ->
            val start = (page - range.first) * pageSize
            installPage(page, if (start >= rows.size) emptyList() else rows.subList(start, minOf(start + pageSize, rows.size)))
        }
    }

    fun retain(range: IntRange) {
        pages.keys.toList().filter { it !in range }.forEach(pages::remove)
    }

    fun installPage(page: Int, rows: List<T>) {
        require(page >= 0 && rows.size <= pageSize)
        // Equal Room emissions must preserve the list and its Item instances.
        if (pages[page] != rows) pages[page] = rows.toList()
    }

    operator fun get(index: Int): T? = if (index < 0) null else pages[index / pageSize]?.getOrNull(index % pageSize)
}
