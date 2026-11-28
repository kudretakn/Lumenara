package dev.lumenara.tv

import androidx.compose.runtime.*
import dev.lumenara.data.*

@Composable
internal fun rememberWp22SeriesCatalog(items: List<Item>): Wp14SeriesCatalog? {
    val state = remember(items) { mutableStateOf<Wp14SeriesCatalog?>(null) }
    LaunchedEffect(items) { state.value = wp22SeriesCatalog(items) }
    return state.value
}

@Composable
internal fun rememberWp22SeriesProgress(catalog: Wp14SeriesCatalog?, marks: List<Mark>): Wp14SeriesProgress {
    val state = remember(catalog) { mutableStateOf(Wp14SeriesProgress()) }
    LaunchedEffect(catalog, marks) {
        state.value = catalog?.let { wp22SeriesProgress(it, marks) } ?: Wp14SeriesProgress()
    }
    return state.value
}
