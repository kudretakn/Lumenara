package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dev.lumenara.data.*
import dev.lumenara.design.Ink
import dev.lumenara.design.*
import dev.lumenara.catalog.displayTitle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.flow.*

@Composable
internal fun Poster(model: LibraryModel, item: Item, modifier: Modifier,
    allowHttp: Boolean? = null, width: Int = 0, height: Int = 0) {
    val image by produceState<String?>(null, item.id, item.logo) { value = model.repo.poster(item) }
    Box(modifier.background(LumenColors.surface2)) {
        LumenIcon(if (item.kind == "SERIES" || item.kind == "EPISODE") LumenIcon.Series else LumenIcon.Film,
            null, Modifier.align(Alignment.TopEnd).padding(Spacing.unit),
            tint = LumenColors.textSecondary.copy(alpha = .4f))
        Text(item.title.displayTitle(), Modifier.align(Alignment.BottomStart).padding(Spacing.unit),
            style = LumenType.cardTitle, color = LumenColors.textPrimary, maxLines = 3,
            overflow = TextOverflow.Ellipsis)
        if (image != null)
            Artwork(model, item, image, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                allowHttp = allowHttp, width = width, height = height)
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

@Composable
internal fun Home(
    model: LibraryModel,
    source: String,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
    lastCard: String = "Canlı TV",
    navigate: (String) -> Unit,
) {
    val recentResult by remember(source) { model.repo.recentLive(source) }.collectAsLoadingState()
    val resumeResult by remember(source) { model.repo.continueWatching(source) }.collectAsLoadingState()
    val recent = recentResult.orEmpty()
    val resume = resumeResult.orEmpty()
    val counts = listOf("LIVE", "MOVIE", "SERIES").map { kind ->
        val groups by remember(source, kind) { model.repo.categoryCounts(kind, source) }
            .collectAsLoadingState()
        groups?.sumOf { it.count }
    }
    LaunchedEffect(counts.all { it != null }) {
        if (counts.all { it != null }) {
            withFrameNanos { }; withFrameNanos { }
            Perf.since("startup", "startup_hub_first_draw")
        }
    }
    val update by model.update.collectAsStateWithLifecycle()
    var focused by rememberSaveable { mutableStateOf(lastCard) }
    val entry = remember { FocusRequester() }
    val list = rememberLazyListState()
    val cards = listOf("Canlı TV", "Filmler", "Diziler", "Listem", "Ara", "Ayarlar")
    val target = if (focused in cards) focused else lastCard
    val returnShelf = remember { focused.takeIf { it == "recent" || it == "resume" } }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(recentResult, resumeResult) {
        if (!restored) {
            if (returnShelf != null && (recentResult == null || resumeResult == null)) return@LaunchedEffect
            when {
                returnShelf == "recent" && recent.isNotEmpty() -> list.scrollToItem(3)
                returnShelf == "resume" && resume.isNotEmpty() -> list.scrollToItem(if (recent.isEmpty()) 3 else 4)
                returnShelf == null -> {
                    list.scrollToItem(0)
                    withFrameNanos { }
                    entry.requestFocus()
                }
                else -> return@LaunchedEffect
            }
            restored = true
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("hub").focusRestorer().focusGroup(), state = list,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { LumenaraLogo(showName = true) }
        items(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                repeat(3) { column ->
                    val index = row * 3 + column
                    val name = cards[index]
                    val caption = if (index < 3 && counts[index] != null) {
                        val number = java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("tr"))
                            .format(counts[index] ?: 0)
                        "$number " + listOf("kanal", "film", "dizi")[index]
                    } else ""
                    NavigationCard(name, caption,
                        Modifier.weight(1f).height(if (row == 0) 200.dp else 110.dp)
                            .testTag("hub-$name").semantics {
                                contentDescription = name
                                if (name == "Ayarlar" && update != null) stateDescription = "Güncelleme hazır"
                            }.then(if (name == target) Modifier.focusRequester(entry) else Modifier)
                            .onFocusChanged { if (it.isFocused) focused = name },
                        update = name == "Ayarlar" && update != null,
                        icon = listOf(2, 3, 4, 5, 0, 6)[index],
                        loadingCaption = index < 3 && counts[index] == null,
                    ) { navigate(name) }
                }
            }
        }
        if (recentResult == null || resumeResult == null) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                items(8) { LoadingCard(Modifier.width(210.dp).height(140.dp)) }
            }
        }
        if (recent.isNotEmpty()) item(key = "recent-live") {
            ChannelShelf(model, "Son kanal", recent, play, { focused = "recent" })
        }
        if (resume.isNotEmpty()) item(key = "resume") {
            Shelf(model, "Kaldığın yerden devam et", resume, "resume", open, { focused = "resume" })
        }
        if (counts.all { it == 0 }) item {
            Text("İçerik eklemek için Ayarlar → IPTV Kaynakları.", color = Ink.muted)
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
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Ara", fontSize = 28.sp)
            Box(Modifier.weight(1f)) { SearchScreen(model, source, open, play) }
        }
        return
    }
    var selectedCategory by rememberSaveable(source, section) { mutableStateOf<String?>(null) }
    var categoryTitle by rememberSaveable(source, section) { mutableStateOf("") }
    val category = selectedCategory?.removePrefix("provider:").takeIf {
        selectedCategory?.startsWith("provider:") == true
    }.orEmpty()
    val shelf = if (section == "Listem") selectedCategory.orEmpty() else ""
    val groups by remember(kind, source) { model.repo.categoryCounts(kind, source) }
        .collectAsLoadingState()
    val favoriteCount by remember(source) { model.repo.shelfCount(source, "", "favorite") }
        .collectAsLoadingState()
    val listedCount by remember(source) { model.repo.shelfCount(source, "", "list") }
        .collectAsLoadingState()
    val cardPages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    BackHandler(selectedCategory != null) { selectedCategory = null }
    if (selectedCategory == null) {
        val entries = if (section == "Listem") {
            if (favoriteCount == null || listedCount == null) null
            else listOf(CategoryEntry("favorite", "Favoriler", favoriteCount ?: 0),
                CategoryEntry("list", "İzleme listem", listedCount ?: 0))
        } else groups?.let {
            listOf(CategoryEntry("all", "Tümü", it.sumOf { group -> group.count }))
                .filter { entry -> entry.count > 0 } + providerCategories(it)
        }
        cardPages.SaveableStateProvider("categories") {
            CategoryGrid(section, entries) { selectedCategory = it.id; categoryTitle = it.name }
        }
        return
    }
    cardPages.SaveableStateProvider("content-$selectedCategory") {
        PosterGridContent(model, section, source, kind, category, shelf, categoryTitle, open, play)
    }
}

@Composable
private fun PosterGridContent(model: LibraryModel, section: String, source: String, kind: String,
    category: String, shelf: String, categoryTitle: String, open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit) {
    var limit by rememberSaveable { mutableIntStateOf(80) }
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
    val result by remember(source, kind, shelf, category, limit, year, genre, language, rating) {
        model.repo.browse(source, kind, category, "", shelf, limit, year, genre, language, rating.toDouble())
    }.collectAsLoadingState()
    val rows = result.orEmpty()
    val loading = result == null
    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.isNotEmpty()) {
            withFrameNanos { }; withFrameNanos { }
            Perf.since("category", "category_grid_first_draw")
        }
    }
    val sources by model.sources.collectAsStateWithLifecycle()
    val httpSources = remember(sources) { sources.orEmpty().filter { it.allowHttp }.map { it.id }.toSet() }
    val density = LocalDensity.current
    var gridWidth by remember { mutableIntStateOf(0) }
    val posterWidth = with(density) { if (gridWidth == 0) 0 else ((gridWidth - 6 * 16.dp.roundToPx()) / 7).coerceAtLeast(1) }
    val posterHeight = (posterWidth * 1.5f).toInt()
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var lastPoster by rememberSaveable(source, category, shelf) { mutableStateOf<String?>(null) }
    val posterFocus = remember { FocusRequester() }
    var positioned by remember(category, shelf) { mutableStateOf(false) }
    var requestPending by remember { mutableStateOf(true) }
    val restoreId = remember { lastPoster }
    val posterTarget = rows.indexOfFirst { it.id == restoreId }.coerceAtLeast(0)
    LaunchedEffect(rows) {
        if (!positioned && rows.isNotEmpty()) {
            grid.scrollToItem(posterTarget)
            positioned = true
        }
    }
    PrefetchArtwork(model, grid, rows, columns = 7)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { wallClockTicks(30_000).collect { now = it } }
    BackHandler(advanced) { closeFilters() }
    LaunchedEffect(rows, loading) {
        if (!loading && rows.isEmpty() && kind in listOf("MOVIE", "SERIES") && !advanced) filterButton.requestFocus()
    }
    val emptyFocus = remember { FocusRequester() }
    LaunchedEffect(rows, loading) { if (!loading && rows.isEmpty() && kind.isEmpty()) emptyFocus.requestFocus() }
    val panelFocus = remember { FocusRequester() }
    // Keep the filter layer over the content instead of consuming another Column slot.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().focusProperties {
            if (advanced) canFocus = false
        }.focusGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.titleGap)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(section, style = LumenType.headline, color = LumenColors.textPrimary)
                Text("${rows.size}${if (rows.size >= limit) "+" else ""} " + if (kind == "SERIES") "dizi" else "film",
                    Modifier.weight(1f), style = LumenType.meta)
                if (kind == "MOVIE" || kind == "SERIES") {
                    val count = listOf(year, genre, language).count { it.isNotEmpty() } +
                        if (rating > 0) 1 else 0
                    LumenButton(onClick = { advanced = true }, style = LumenButtonStyle.Secondary,
                        modifier = Modifier.testTag("filter-button")
                            .focusRequester(filterButton)) {
                        LumenIcon(LumenIcon.Filter, null)
                        Text(if (count == 0) "Filtrele" else "Filtrele ($count)")
                    }
                }
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(7),
                state = grid,
                modifier = Modifier.weight(1f).onSizeChanged { gridWidth = it.width }.testTag("poster-grid").focusRestorer().focusGroup(),
                // TV safe area plus the title/year below the focusable poster.
                contentPadding = PaddingValues(top = 8.dp, bottom = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (loading) loadingCards(224.dp)
                if (!loading && rows.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(7) }) {
                    Column {
                        Text("Eşleşen içerik yok.", color = Ink.muted)
                        if (kind.isEmpty()) {
                            val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                            Button(onClick = { back?.onBackPressed() }, modifier = Modifier.focusRequester(emptyFocus)) { Text("Geri") }
                        }
                    }
                }
                items(rows.size, key = { rows[it].id }, span = { index ->
                    androidx.compose.foundation.lazy.grid.GridItemSpan(if (rows[index].kind == "LIVE") 2 else 1)
                }) { index ->
                    val entry = rows[index]
                    if (index == posterTarget && positioned && requestPending) LaunchedEffect(Unit) {
                        withFrameNanos { }
                        posterFocus.requestFocus()
                        requestPending = false
                    }
                    val cardModifier = Modifier.fillMaxWidth()
                        .then(if (index == posterTarget) Modifier.focusRequester(posterFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) lastPoster = entry.id }
                    if (entry.kind == "LIVE")
                        ChannelCard(model, entry, now, { play(entry, rows.filter { it.kind == "LIVE" }) }, { lastPoster = it.id },
                            modifier = cardModifier, operations = { open(entry) })
                    else GridPoster(model, entry, open, { lastPoster = it.id }, modifier = cardModifier,
                        artworkHttp = entry.source in httpSources,
                        artworkWidth = posterWidth, artworkHeight = posterHeight)
                }
                if (rows.size >= limit) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(7) }) {
                    Button(onClick = { limit += 80 }) { Text("Devamını göster") }
                }
            }
        }
        if (advanced) {
            LaunchedEffect(advanced) { panelFocus.requestFocus() }
            Box(Modifier.fillMaxSize().focusGroup()) {
                Box(Modifier.fillMaxSize().background(LumenColors.dim))
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    Panel(
                        Modifier.width(400.dp).fillMaxHeight().testTag("filter-panel")) {
                        LazyColumn(Modifier.fillMaxSize().testTag("filter-fields")
                            .focusRequester(panelFocus).focusGroup()
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
}

/** Grid captions reserve their space so a focus change never moves the next row. */
@Composable
private fun GridPoster(model: LibraryModel, item: Item, open: (Item) -> Unit,
    focus: (Item) -> Unit, modifier: Modifier, artworkHttp: Boolean,
    artworkWidth: Int, artworkHeight: Int) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        FocusCard(onClick = { open(item) }, modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = item.title }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) focus(item) }) {
            Poster(model, item, Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                allowHttp = artworkHttp, width = artworkWidth, height = artworkHeight)
        }
        Column(Modifier.padding(top = Spacing.unit).alpha(if (focused) 1f else 0f)) {
            Text(item.title.displayTitle(), style = LumenType.cardTitle, color = LumenColors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.year, style = LumenType.meta, maxLines = 1)
        }
    }
}
