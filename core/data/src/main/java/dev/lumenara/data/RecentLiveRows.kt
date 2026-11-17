package dev.lumenara.data

import dev.lumenara.catalog.isAdultCategory

/** Room returns an unordered bounded ID lookup; the saved order is the shelf order. */
internal fun visibleRecentIds(ids: List<String>, categories: Map<String, String>, showAdult: Boolean): List<String> =
    ids.filter { id -> categories[id]?.let { showAdult || !isAdultCategory(it) } == true }
