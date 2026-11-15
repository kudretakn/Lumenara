package dev.lumenara.catalog

data class SearchRequest(val query: String, val playFromSearch: Boolean)

/** Converts supported Android search actions into a platform independent request. */
fun searchRequest(action: String?, query: String?): SearchRequest? {
    val cleanQuery = query?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (action) {
        "android.intent.action.SEARCH", "com.google.android.gms.actions.SEARCH_ACTION" ->
            SearchRequest(cleanQuery, false)
        "android.media.action.MEDIA_PLAY_FROM_SEARCH" -> SearchRequest(cleanQuery, true)
        else -> null
    }
}

/** Returns the matching channel index only when the normalized title is unique. */
fun exactChannelIndex(query: String, channelNames: List<String>): Int? {
    val normalized = searchable(query)
    if (normalized.isBlank()) return null
    val matches = channelNames.indices.filter { searchable(channelNames[it]) == normalized }
    return matches.singleOrNull()
}
