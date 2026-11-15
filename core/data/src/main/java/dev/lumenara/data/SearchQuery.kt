package dev.lumenara.data

import dev.lumenara.catalog.searchable
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Share this settled value across catalog shelves and programme search. */
@OptIn(FlowPreview::class)
fun Flow<String>.debouncedSearchQuery(): Flow<String> =
    map { searchable(it).takeIf { query -> query.length >= 2 }.orEmpty() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else 300L }
        .distinctUntilChanged()
