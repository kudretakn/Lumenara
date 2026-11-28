package dev.lumenara.tv

import androidx.compose.runtime.*
import dev.lumenara.data.*

@Composable
internal fun rememberWp14SeriesCatalog(items: List<Item>): Wp14SeriesCatalog? {
    val state = remember(items) { mutableStateOf<Wp14SeriesCatalog?>(null) }
    LaunchedEffect(items) { state.value = wp14SeriesCatalog(items) }
    return state.value
}

@Composable
internal fun rememberWp14SeriesProgress(catalog: Wp14SeriesCatalog?, marks: List<Mark>): Wp14SeriesProgress {
    val state = remember(catalog) { mutableStateOf(Wp14SeriesProgress()) }
    LaunchedEffect(catalog, marks) {
        state.value = catalog?.let { wp14SeriesProgress(it, marks) } ?: Wp14SeriesProgress()
    }
    return state.value
}
