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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
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
import dev.lumenara.design.*
import dev.lumenara.catalog.displayTitle
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.flow.*

@Composable
internal fun Poster(model: LibraryModel, item: Item, modifier: Modifier,
    allowHttp: Boolean? = null, width: Int = 0, height: Int = 0) {
    val image by produceState<String?>(null, item.id, item.logo) { value = model.repo.poster(item) }
    Artwork(model, item.copy(title = item.title.displayTitle()), image, modifier, contentScale = ContentScale.Crop,
        allowHttp = allowHttp, width = width, height = height)
}

/** Hub shelves keep their text stationary while the 16:9 image receives focus. */
@Composable
private fun HubShelfTile(
    item: Item,
    click: () -> Unit,
    focus: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var restoreFocus by rememberSaveable(item.id) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (restoreFocus) {
            withFrameNanos { }
            requester.requestFocus()
            restoreFocus = false
        }
    }
    FocusCard(
        onClick = { restoreFocus = true; click() },
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .focusRequester(requester)
            .semantics { contentDescription = item.title }
            .onFocusChanged { if (it.isFocused) focus() },
        content = content,
    )
}

@Composable
private fun HubChannelShelf(
    model: LibraryModel,
    rows: List<Item>,
    play: (Item, List<Item>) -> Unit,
    focus: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { wallClockTicks(30_000).collect { now = it } }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        Text("SON KANAL", style = LumenType.caption, color = LumenColors.textSecondary)
        LazyRow(
            Modifier.testTag("channel-shelf-Son kanal").focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            // Leave room for the unchanged focus scale and ring at the row edges.
            contentPadding = PaddingValues(Spacing.unit),
        ) {
            items(rows, key = { it.id }) { item ->
                val slots = liveProgrammes(model, item, now)
                val current = slots.firstOrNull { now >= it.start && now < it.end }
                val logo by produceState<String?>(null, item.id, item.logo) {
                    value = model.repo.poster(item)
                }
                Column(Modifier.width(110.dp), verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    HubShelfTile(item, { play(item, rows) }, focus,
                        Modifier.testTag("live-channel-${item.id}")) {
                        HubArtwork(model, item, logo, channel = true)
                    }
                    Text(item.title, style = LumenType.label, color = LumenColors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current?.title.orEmpty(), style = LumenType.meta,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (current != null) ProgressLine(
                        (now - current.start).toFloat() / (current.end - current.start).coerceAtLeast(1),
                    )
                }
            }
        }
    }
}

@Composable
private fun HubResumeShelf(model: LibraryModel, rows: List<Item>, open: (Item) -> Unit, focus: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        Text("KALDIĞIN YERDEN DEVAM ET", style = LumenType.caption, color = LumenColors.textSecondary)
        LazyRow(
            Modifier.testTag("shelf-Kaldığın yerden devam et").focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(Spacing.unit),
        ) {
            items(rows, key = { it.id }) { item ->
                val episodes by remember(item.id) {
                    if (item.kind == "SERIES") model.repo.episodes(item.id) else flowOf(emptyList<Item>())
                }.collectAsStateWithLifecycle(emptyList())
                val marks by remember(item.id) {
                    if (item.kind == "SERIES") model.repo.library.episodeMarks(item.id) else flowOf(emptyList<Mark>())
                }.collectAsStateWithLifecycle(emptyList())
                val continuing = episodes.continueItem(marks)
                val observedMark by remember(item.id) {
                    model.repo.library.observeMark(item.id)
                }.collectAsStateWithLifecycle(null)
                val mark = if (item.kind == "SERIES") marks.firstOrNull { it.id == continuing?.id } else observedMark
                val backdrop by produceState<String?>(null, item.id, item.backdrop) {
                    value = model.repo.backdrop(item)
                }
                val episode = continuing?.seriesEpisode()
                val remaining = mark?.takeIf { it.duration > 0 }?.let {
                    "${((it.duration - it.position).coerceAtLeast(0) + 59_999) / 60_000} dk kaldı"
                }
                val caption = listOfNotNull(episode?.let { "S${it.season} · B${it.episode}" }, remaining)
                    .joinToString(" · ").ifBlank { "İzlemeye devam et" }
                Column(Modifier.width(110.dp), verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    HubShelfTile(item, { open(item) }, focus) {
                        HubArtwork(model, item, backdrop)
                        if ((mark?.duration ?: 0) > 0) ProgressLine(
                            (mark?.position ?: 0).toFloat() / checkNotNull(mark).duration,
                            Modifier.align(Alignment.BottomStart),
                        )
                    }
                    Text(item.title, style = LumenType.label, color = LumenColors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(caption, style = LumenType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Local 16:9 fallback; the shared poster fallback and shared channel cards belong to D1/D3. */
@Composable
private fun HubArtwork(model: LibraryModel, item: Item, address: String?, channel: Boolean = false) {
    if (channel) {
        ChannelLogo(model, item, address, Modifier.fillMaxSize())
        return
    }
    Box(Modifier.fillMaxSize().background(LumenColors.surface2), contentAlignment = Alignment.BottomStart) {
        if (address == null) {
            Text(item.title, Modifier.padding(Spacing.cardGap), style = LumenType.label,
                color = LumenColors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            LumenIcon(if (item.kind == "SERIES") LumenIcon.Series else LumenIcon.Film,
                contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.cardGap),
                tint = LumenColors.textPrimary.copy(alpha = .4f))
        } else Artwork(model, item, address, Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, role = "backdrop")
    }
}

@Composable
private fun HubNavigationCard(
    model: LibraryModel, name: String, caption: String, icon: LumenIcon,
    modifier: Modifier, compact: Boolean, loading: Boolean, update: Boolean,
    artworkItem: Item?, click: () -> Unit,
) {
    val backdrop by produceState<String?>(null, artworkItem?.id, artworkItem?.backdrop) {
        // Only a real wide image is eligible; never substitute a channel logo or poster.
        value = artworkItem?.let { model.repo.backdrop(it) }
    }
    FocusCard(click, modifier, focusedScale = Motion.hubFocusScale) {
        if (!compact && backdrop != null) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Radii.card))) {
                Artwork(model, artworkItem, backdrop, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.6f),
                    contentScale = ContentScale.Crop, role = "backdrop")
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                    LumenColors.surface2, LumenColors.surface2.copy(alpha = .65f),
                ))))
            }
        }
        if (compact) Row(Modifier.fillMaxSize().padding(horizontal = Spacing.cardGap),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LumenIcon(icon, null)
            Text(name, style = LumenType.cardTitle, color = LumenColors.textPrimary)
            if (update) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(6.dp).background(LumenColors.accent, RoundedCornerShape(3.dp)))
            }
        } else Column(Modifier.fillMaxSize().padding(Spacing.unit)) {
            LumenIcon(icon, null, size = 24.dp)
            Spacer(Modifier.weight(1f))
            Text(name, style = LumenType.cardTitle, color = LumenColors.textPrimary)
            Spacer(Modifier.height(Spacing.iconText))
            if (loading) Box(Modifier.width(72.dp).height(18.dp)
                .background(LumenColors.surface3, RoundedCornerShape(4.dp)).testTag("list-loading"))
            else Text(caption, style = LumenType.meta)
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
    sourceSelector: @Composable () -> Unit = {},
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
        contentPadding = PaddingValues(bottom = Spacing.shelfGap),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        item {
            Row(Modifier.fillMaxWidth().height(45.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LumenaraLogo(showName = true)
                Spacer(Modifier.weight(1f))
                Box(Modifier.widthIn(max = 480.dp)) { sourceSelector() }
            }
        }
        items(2) { row ->
            Row(Modifier.padding(bottom = if (row == 1) Spacing.cardGap else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { column ->
                    val index = row * 3 + column
                    val name = cards[index]
                    val caption = if (index < 3 && counts[index] != null) {
                        val number = java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("tr"))
                            .format(counts[index] ?: 0)
                        "$number " + listOf("kanal", "film", "dizi")[index]
                    } else ""
                    // Measure the grid slot independently of the card's focus transform.
                    Box(Modifier.weight(1f).height(if (row == 0) 100.dp else 40.dp)
                        .testTag("hub-slot-$name")) {
                        HubNavigationCard(model, name, caption,
                            listOf(LumenIcon.Tv, LumenIcon.Film, LumenIcon.Series, LumenIcon.Bookmark, LumenIcon.Search, LumenIcon.Settings)[index],
                            Modifier.fillMaxSize()
                                .testTag("hub-$name").semantics {
                                    contentDescription = name
                                    if (name == "Ayarlar" && update != null) stateDescription = "Güncelleme hazır"
                                }.then(if (name == target) Modifier.focusRequester(entry) else Modifier)
                                .onFocusChanged { if (it.isFocused) focused = name },
                            update = name == "Ayarlar" && update != null,
                            compact = row == 1,
                            loading = index < 3 && counts[index] == null,
                            artworkItem = when (index) {
                                0 -> recent.firstOrNull()
                                1 -> resume.firstOrNull { it.kind == "MOVIE" }
                                2 -> resume.firstOrNull { it.kind == "SERIES" }
                                else -> null
                            },
                        ) { navigate(name) }
                    }
                }
            }
        }
        if (recentResult == null || resumeResult == null) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(4) {
                    Box(Modifier.width(110.dp).aspectRatio(16f / 9f)
                        .background(LumenColors.surface2, RoundedCornerShape(Radii.card)).testTag("list-loading"))
                }
            }
        }
        if (recent.isNotEmpty()) item(key = "recent-live") {
            HubChannelShelf(model, recent, play, { focused = "recent" })
        }
        if (resume.isNotEmpty()) item(key = "resume") {
            Box(Modifier.padding(top = if (recent.isNotEmpty()) Spacing.cardGap else 0.dp)) {
                HubResumeShelf(model, resume, open, { focused = "resume" })
            }
        }
        if (counts.all { it == 0 }) item {
            Text("İçerik eklemek için Ayarlar bölümünden IPTV Kaynakları’nı açın.",
                style = LumenType.body, color = LumenColors.textSecondary)
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

private const val POSTER_GRID_COLUMNS = 8
private const val POSTER_PAGE_SIZE = POSTER_GRID_COLUMNS * 10

@Composable
private fun PosterGridContent(model: LibraryModel, section: String, source: String, kind: String,
    category: String, shelf: String, categoryTitle: String, open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit) {
    var limit by rememberSaveable { mutableIntStateOf(POSTER_PAGE_SIZE) }
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
    val posterWidth = with(density) { if (gridWidth == 0) 0 else ((gridWidth - (POSTER_GRID_COLUMNS - 1) * Spacing.cardGap.roundToPx()) / POSTER_GRID_COLUMNS).coerceAtLeast(1) }
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
    PrefetchArtwork(model, grid, rows, columns = POSTER_GRID_COLUMNS, channelSpan = 2)
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
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(POSTER_GRID_COLUMNS),
                state = grid,
                modifier = Modifier.weight(1f).onSizeChanged { gridWidth = it.width }.testTag("poster-grid").focusRestorer().focusGroup(),
                // TV safe area plus the title/year below the focusable poster.
                contentPadding = PaddingValues(top = 8.dp, bottom = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (loading) loadingCards(224.dp)
                if (!loading && rows.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
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
                if (rows.size >= limit) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Button(onClick = { limit += POSTER_PAGE_SIZE }) { Text("Devamını göster") }
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
