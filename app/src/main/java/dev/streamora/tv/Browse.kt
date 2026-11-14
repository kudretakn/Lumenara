package dev.streamora.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.compose.AsyncImage
import dev.streamora.data.*
import dev.streamora.design.Ink
import dev.streamora.design.Pill
import dev.streamora.design.GlassPanel
import kotlinx.coroutines.flow.*

@Composable
internal fun Poster(model: LibraryModel, item: Item, modifier: Modifier) {
    val image by produceState<String?>(null, item.logo) { value = model.repo.poster(item) }
    Box(modifier.background(Ink.surface), contentAlignment = Alignment.Center) {
        Text(item.title.take(1), color = Ink.accent, fontSize = 30.sp)
        if (image != null)
            AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun Shelf(
    model: LibraryModel,
    title: String,
    rows: List<Item>,
    shelf: String,
    open: (Item) -> Unit,
    focus: (Item) -> Unit,
) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            title,
            fontSize = 19.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier.testTag("shelf-$title").focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            contentPadding = PaddingValues(5.dp),
        ) {
            items(rows, key = { it.id }) { entry ->
                MovieCard(
                    model,
                    entry,
                    entry.kind != "LIVE" && shelf != "resume",
                    open,
                    focus,
                    progress = shelf == "resume",
                )
            }
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
internal fun Home(
    model: LibraryModel,
    source: String,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
    info: MovieInfo,
    feature: (Item?) -> Unit,
    interaction: Long,
) {
    val collections by remember(source) { model.repo.library.collections(source) }
        .collectAsStateWithLifecycle(emptyList())
    val candidates by remember(source) { model.repo.library.homeFeatures(source) }
        .collectAsStateWithLifecycle(emptyList())
    val recent by remember(source) {
        model.prefs.recentLive.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else model.repo.library.liveByIds(source, ids).map { rows ->
                val byId = rows.associateBy { it.id }
                ids.mapNotNull { byId[it] }
            }
        }
    }.collectAsStateWithLifecycle(emptyList())
    val onAir by remember(source) {
        model.repo.library.browse(source, "LIVE", "", "", "favorite", 20)
            .flatMapLatest { favorites ->
                if (favorites.isNotEmpty()) flowOf(favorites)
                else model.repo.library.firstLiveCategory(source)
            }
    }.collectAsStateWithLifecycle(emptyList())
    val shelfSpecs = listOf(
        Triple("Kaldığın yerden devam et", "", "resume"),
        Triple("Son eklenen filmler", "MOVIE", "recent"),
        Triple("Yüksek puanlı filmler", "MOVIE", "popular"),
        Triple("Diziler", "SERIES", ""),
        Triple("İzleme listem", "", "list"),
    ) + collections.map { Triple(it, "MOVIE", "collection") } +
        Triple("İzlediklerinize göre", "", "suggest")
    val shelves = shelfSpecs.map { (title, kind, shelf) ->
        key(title, shelf) {
            val rows by remember(source, title, kind, shelf) {
                when (shelf) {
                    "collection" -> model.repo.library.members(source, title)
                    "suggest" -> model.repo.library.suggestions(source)
                    else -> model.repo.library.browse(source, kind, "", "", shelf, 24)
                }
            }.collectAsStateWithLifecycle(emptyList())
            Triple(title, shelf, rows)
        }
    }.filter { it.third.isNotEmpty() }
    var page by rememberSaveable(source) { mutableIntStateOf(0) }
    var shelvesFocused by rememberSaveable(source) { mutableStateOf(false) }
    var focusedId by rememberSaveable(source) { mutableStateOf<String?>(null) }
    val focused = (recent + onAir + shelves.flatMap { it.third } + candidates)
        .firstOrNull { it.id == focusedId }
    val rotating = candidates.getOrNull(page % candidates.size.coerceAtLeast(1))
    val hero = if (shelvesFocused) focused ?: rotating else rotating
    LaunchedEffect(source, candidates, shelvesFocused, interaction) {
        if (!shelvesFocused && candidates.size > 1) {
            wallClockTicks(9_000).drop(1).collect {
                page = (page + 1) % candidates.size
            }
        }
    }
    LaunchedEffect(hero) { feature(hero) }
    val choose: (Item) -> Unit = { focusedId = it.id }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Leave room for a complete portrait shelf: heading, artwork, title and year.
        // The enclosing screen already reserves the TV's 27 dp bottom safe area.
        val heroHeight = maxHeight * .48f
        Column {
            Box(Modifier.fillMaxWidth().height(heroHeight)
                .onFocusChanged { if (it.hasFocus) shelvesFocused = false }
                .focusRestorer()
                .focusGroup()) {
                hero?.let {
                    Hero(model, it, info, open, play, Modifier.padding(top = 12.dp))
                }
                if (candidates.isNotEmpty())
                    Row(Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        candidates.forEachIndexed { index, _ ->
                            Box(Modifier.size(if (index == page % candidates.size) 8.dp else 6.dp)
                                .background(if (index == page % candidates.size) Ink.focusRing else Ink.stroke,
                                    androidx.compose.foundation.shape.CircleShape))
                        }
                    }
            }
            LazyColumn(
                Modifier.weight(1f).testTag("home-shelves").onFocusChanged { shelvesFocused = it.hasFocus }
                    .focusRestorer().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            ) {
                if (recent.isNotEmpty()) item(key = "recent-live") {
                    ChannelShelf(model, "Son izlenen kanallar", recent, play, choose)
                }
                if (onAir.isNotEmpty()) item(key = "on-air") {
                    ChannelShelf(model, "Şu an yayında — favori kanallar", onAir, play, choose)
                }
                items(shelves, key = { "${it.second}:${it.first}" }) { (title, shelf, rows) ->
                    Shelf(model, title, rows, shelf, open, choose)
                }
            }
        }
    }
}

@Composable
internal fun Browse(
    model: LibraryModel,
    section: String,
    source: String,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
    returnToChannel: String? = null,
    onChannelFocusRestored: () -> Unit = {},
) {
    val kind =
        when (section) {
            "Canlı TV" -> "LIVE"
            "Filmler" -> "MOVIE"
            "Diziler" -> "SERIES"
            else -> ""
        }
    if (kind == "LIVE") {
        LiveScreen(model, source, open, play, returnToChannel, onChannelFocusRestored)
        return
    }
    if (section == "Ara") {
        SearchScreen(model, source, open, play)
        return
    }
    var listTab by rememberSaveable { mutableStateOf("Favoriler") }
    val shelf = if (section == "Listem") {
        if (listTab == "Favoriler") "favorite" else "list"
    } else ""
    var category by rememberSaveable(source) { mutableStateOf("") }
    var limit by rememberSaveable(source, category, shelf) { mutableIntStateOf(80) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var year by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableIntStateOf(0) }
    val filterButton = remember { FocusRequester() }
    var restoreFilterFocus by remember { mutableStateOf(false) }
    val closeFilters: () -> Unit = {
        restoreFilterFocus = true
        advanced = false
    }
    LaunchedEffect(advanced) {
        if (!advanced && restoreFilterFocus) {
            filterButton.requestFocus()
            restoreFilterFocus = false
        }
    }
    LaunchedEffect(source, kind, shelf, category, limit, year, genre, language, rating) {
        model.filter.value = Filter(source, kind, category, "", shelf, limit,
            year, genre, language, rating.toDouble())
    }
    val rows by model.results.collectAsStateWithLifecycle()
    val groups by remember(kind, source) { model.repo.library.categories(kind, source) }
        .collectAsStateWithLifecycle(emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { wallClockTicks(30_000).collect { now = it } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (section == "Listem") {
            LazyRow(contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(listOf("Favoriler", "İzleme Listem")) { tab ->
                    Pill(tab, listTab == tab, onClick = { listTab = tab })
                }
            }
        }
        if (kind == "MOVIE" || kind == "SERIES") {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyRow(Modifier.weight(1f).focusRestorer().focusGroup(),
                    contentPadding = PaddingValues(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("") + groups, key = { it }) { group ->
                        Pill(group.ifEmpty { "Tümü" }, category == group,
                            onClick = { category = group })
                    }
                }
                val count = listOf(year, genre, language).count { it.isNotEmpty() } +
                    if (rating > 0) 1 else 0
                Button(onClick = { advanced = true },
                    modifier = Modifier.testTag("filter-button")
                        .focusRequester(filterButton)) {
                    Text(if (count == 0) "Filtrele" else "Filtrele ($count)")
                }
            }
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(6),
            modifier = Modifier.weight(1f).testTag("poster-grid").focusRestorer().focusGroup(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (rows.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(6) }) {
                Text("Eşleşen içerik yok.", color = Ink.muted)
            }
            items(rows.size, key = { rows[it].id }, span = { index ->
                androidx.compose.foundation.lazy.grid.GridItemSpan(if (rows[index].kind == "LIVE") 2 else 1)
            }) { index ->
                val entry = rows[index]
                if (entry.kind == "LIVE")
                    ChannelCard(model, entry, now, { play(entry, rows.filter { it.kind == "LIVE" }) }, {})
                else MovieCard(model, entry, true, open, modifier = Modifier.fillMaxWidth())
            }
            if (rows.size >= limit) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(6) }) {
                Button(onClick = { limit += 80 }) { Text("Devamını göster") }
            }
        }
    }
    if (advanced) {
        androidx.compose.ui.window.Dialog(onDismissRequest = closeFilters,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            val panelFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { panelFocus.requestFocus() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                GlassPanel(
                    Modifier.width(360.dp).fillMaxHeight().testTag("filter-panel")) {
                    LazyColumn(Modifier.fillMaxSize().focusRequester(panelFocus).focusGroup()
                        .padding(horizontal = 28.dp, vertical = 27.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { Text("İçerik filtreleri", fontSize = 24.sp) }
                        item { Field("Yıl", year, { year = it.take(4) }) }
                        item { Field("Tür", genre, { genre = it }) }
                        item { Field("Dil", language, { language = it }) }
                        item { Button(onClick = { rating = (rating + 1) % 10 }) { Text("Puan ≥ $rating") } }
                        item { Button(onClick = closeFilters) { Text("Sonuçları göster") } }
                    }
                }
            }
        }
    }
}
