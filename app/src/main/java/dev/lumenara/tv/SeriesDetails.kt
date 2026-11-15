package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dev.lumenara.catalog.displayTitle
import dev.lumenara.catalog.episodeName
import dev.lumenara.catalog.watched
import dev.lumenara.data.*
import dev.lumenara.design.*

@Composable
internal fun SeriesDetails(model: LibraryModel, series: Item, play: (Item, List<Item>) -> Unit, back: () -> Unit) {
    val raw by remember(series.id) { model.repo.episodes(series.id) }.collectAsLoadingState()
    val marks by remember(series.id) { model.repo.library.episodeMarks(series.id) }
        .collectAsStateWithLifecycle(emptyList())
    val mark by remember(series.id) { model.repo.library.observeMark(series.id) }
        .collectAsStateWithLifecycle(null)
    val episodes = remember(raw) { raw.orEmpty().seriesOrder() }
    val byId = remember(marks) { marks.associateBy { it.id } }
    val seasons = remember(episodes) { episodes.groupBy { it.seriesEpisode().season }.toSortedMap() }
    // A tab still enters its season and Back still returns to that tab before leaving details.
    var season by rememberSaveable(series.id) { mutableStateOf<Int?>(null) }
    var previewSeason by rememberSaveable(series.id) { mutableIntStateOf(1) }
    var seriesFocus by rememberSaveable(series.id) { mutableStateOf("series-play") }
    var loaded by rememberSaveable(series.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Item?>(null) }
    var restoreEpisode by remember { mutableStateOf("") }
    val notice by model.notice.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    LaunchedEffect(series.id) {
        if (!loaded) model.job("Bölüm listesi güncellendi.") { model.repo.expand(series); loaded = true }
    }
    val loading = raw == null || (episodes.isEmpty() && busy)
    val info = cinemaInfo(model, series)
    val next = episodes.continueItem(marks)
    val name = next?.let { episodeName(it.title, it.category) }
    val hasHistory = marks.any { it.position > 0 }
    val selectedSeason = season ?: previewSeason.takeIf { it in seasons } ?: seasons.keys.firstOrNull() ?: 1
    val primary = remember { FocusRequester() }
    val content = rememberLazyListState()
    LaunchedEffect(series.id) {
        if (season == null && seriesFocus == "series-play") {
            withFrameNanos { }; primary.requestFocus()
        }
    }
    BackHandler(season != null && menu == null) { season = null }
    fun closeMenu() { restoreEpisode = menu?.id.orEmpty(); menu = null }
    BackHandler(menu != null) { closeMenu() }
    Box(Modifier.fillMaxSize().testTag(if (season == null) "series-page" else "season-page")) {
        CinemaBackdrop(model, series, info)
        LazyColumn(Modifier.fillMaxSize().testTag("details-content")
            .focusProperties { if (menu != null) canFocus = false }.focusGroup(), state = content,
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            item { DetailHeading(series, info) }
            if (info.summary.isNotBlank()) item {
                Text(info.summary, Modifier.widthIn(max = 560.dp), style = LumenType.body,
                    color = LumenColors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit), verticalAlignment = Alignment.CenterVertically) {
                    LumenButton(onClick = {
                        if (next != null) play(next, episodes)
                        else model.job("Bölüm listesi güncellendi.") { model.repo.expand(series); loaded = true }
                    }, modifier = Modifier.testTag("series-play").focusRequester(primary)
                        .onFocusChanged { if (it.isFocused) seriesFocus = "series-play" }) {
                        LumenIcon(LumenIcon.Play, null)
                        Column {
                            Text(if (name == null) { if (loading) "Oynat" else "Bölümleri yükle" }
                                else if (hasHistory) "Devam et · S${name.season} B${name.episode}"
                                else "Oynat · ${name.episode}. Bölüm")
                            byId[next?.id]?.takeIf { it.duration > 0 && it.position > 0 }?.let {
                                ProgressLine(it.position.toFloat() / it.duration, Modifier.width(160.dp))
                            }
                        }
                    }
                    DetailSecondaryActions(model, series, mark, info)
                }
            }
            item {
                LazyRow(Modifier.testTag("season-shelf").focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
                    if (loading) items(3) { LoadingCard(Modifier.width(112.dp).height(48.dp)) }
                    items(seasons.keys.toList(), key = { it }) { number ->
                        val target = remember { FocusRequester() }
                        val rows = seasons.getValue(number)
                        val complete = rows.all { ep -> byId[ep.id]?.let { watched(it.position, it.duration) } == true }
                        if (season == null && seriesFocus == "season-$number") LaunchedEffect(Unit) {
                            withFrameNanos { }; target.requestFocus()
                        }
                        SeasonTab(number, selectedSeason == number, complete,
                            Modifier.testTag("season-$number").focusRequester(target)
                                .onFocusChanged { if (it.isFocused) seriesFocus = "season-$number" }) {
                            if (season == number) restoreEpisode = rows.firstOrNull()?.id.orEmpty()
                            previewSeason = number
                            season = number
                        }
                    }
                }
            }
            item {
                key(selectedSeason) {
                    EpisodeShelf(model, seasons[selectedSeason].orEmpty(), episodes, byId, loading,
                        enter = season != null || seriesFocus.startsWith("episode-"), restore = restoreEpisode,
                        restored = { restoreEpisode = "" }, play = play, actions = { menu = it },
                        focused = { if (season == null) seriesFocus = "episode-${it.id}" })
                }
            }
            if (!loading && episodes.isEmpty()) item {
                Text(if (loaded) "Sağlayıcı bölüm bildirmedi." else notice, style = LumenType.meta)
                if (!busy) LumenButton(onClick = {
                    model.job("Bölüm listesi güncellendi.") { model.repo.expand(series); loaded = true }
                }) { Text("Yeniden dene") }
            }
            item { DetailCast(info) }
        }
        menu?.let { ep ->
            val focus = remember { FocusRequester() }
            LaunchedEffect(ep.id) { focus.requestFocus() }
            Panel(Modifier.align(Alignment.Center).width(400.dp).testTag("episode-actions")) {
                Column(Modifier.padding(Spacing.titleGap), verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    Text(episodeName(ep.title).title.displayTitle(), style = LumenType.cardTitle, maxLines = 2)
                    ListRow(onClick = { model.watched(ep, true); closeMenu() }, modifier = Modifier.focusRequester(focus)) {
                        Text("İzlendi olarak işaretle", style = LumenType.label)
                    }
                    ListRow(onClick = { model.watched(ep, false); closeMenu() }) {
                        Text("İzlenmedi olarak işaretle", style = LumenType.label)
                    }
                    ListRow(onClick = { closeMenu() }) { Text("Kapat", style = LumenType.label) }
                }
            }
        }
    }
}

/** D0 has no text tab; keep this detail-only tab flat with a gold selection line. */
@Composable
private fun SeasonTab(number: Int, selected: Boolean, complete: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Column(Modifier.width(IntrinsicSize.Max)) {
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(Radii.button)
        Surface(onClick = onClick, modifier = modifier.height(48.dp).semantics {
            this.selected = selected
            stateDescription = if (complete) "İzlendi" else "İzlenmedi"
        }, interactionSource = interactions,
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
                contentColor = LumenColors.textSecondary, focusedContainerColor = LumenColors.focus,
                focusedContentColor = LumenColors.onFocus, pressedContainerColor = LumenColors.surface3,
                pressedContentColor = LumenColors.textPrimary)) {
            Row(Modifier.padding(horizontal = Spacing.cardGap), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.iconText)) {
                Text("Sezon $number", style = LumenType.label)
                if (complete) LumenIcon(LumenIcon.Check, "İzlendi")
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (selected) LumenColors.accent else Color.Transparent))
    }
}

@Composable
private fun EpisodeShelf(model: LibraryModel, episodes: List<Item>, all: List<Item>, marks: Map<String, Mark>,
    loading: Boolean, enter: Boolean, restore: String, restored: () -> Unit,
    play: (Item, List<Item>) -> Unit, actions: (Item) -> Unit, focused: (Item) -> Unit) {
    val list = rememberLazyListState()
    var lastFocus by rememberSaveable { mutableStateOf("") }
    var entered by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    LaunchedEffect(episodes, enter, restore) {
        if (episodes.isNotEmpty() && (enter && !entered || restore.isNotEmpty())) {
            val index = episodes.indexOfFirst { it.id == restore.ifBlank { lastFocus } }.coerceAtLeast(0)
            list.scrollToItem(index)
            target = episodes[index].id
        }
        entered = enter && episodes.isNotEmpty()
    }
    LazyRow(Modifier.testTag("episode-list").focusRestorer().focusGroup(), state = list,
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        if (loading) items(4) { LoadingCard(Modifier.width(240.dp).height(200.dp)) }
        items(episodes, key = { it.id }) { ep ->
            val mark = marks[ep.id]
            val duration = mark?.duration ?: 0
            val position = mark?.position ?: 0
            val name = episodeName(ep.title, ep.category)
            val done = watched(position, duration)
            val focus = remember { FocusRequester() }
            if (target == ep.id) LaunchedEffect(target) {
                withFrameNanos { }; focus.requestFocus(); target = ""; restored()
            }
            Column(Modifier.width(240.dp)) {
                FocusCard(onClick = { play(ep, all) }, onLongClick = { actions(ep) },
                    modifier = Modifier.fillMaxWidth().testTag("episode-${ep.id}").focusRequester(focus)
                        .onFocusChanged { if (it.isFocused) { lastFocus = ep.id; focused(ep) } }
                        .semantics { stateDescription = if (done) "İzlendi" else if (position > 0) "Yarım kaldı" else "İzlenmedi" }) {
                    Poster(model, ep, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    if (duration > 0 && position > 0) ProgressLine(position.toFloat() / duration, Modifier.align(Alignment.BottomStart))
                }
                Row(Modifier.padding(top = Spacing.unit), verticalAlignment = Alignment.CenterVertically) {
                    Text("${name.episode}. ${name.title.displayTitle()}", Modifier.weight(1f), style = LumenType.cardTitle,
                        color = LumenColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (done) LumenIcon(LumenIcon.Check, "İzlendi", size = IconSizes.meta)
                }
                if (duration > 1) Text("${duration / 60_000} dk", style = LumenType.meta)
            }
        }
    }
}
