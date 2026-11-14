package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dev.lumenara.catalog.episodeName
import dev.lumenara.catalog.watched
import dev.lumenara.data.*
import dev.lumenara.design.*

@Composable
internal fun SeriesDetails(model: LibraryModel, series: Item, play: (Item, List<Item>) -> Unit, back: () -> Unit) {
    val raw by remember(series.id) { model.repo.library.episodes(series.id) }
        .collectAsStateWithLifecycle(emptyList())
    val marks by remember(series.id) { model.repo.library.episodeMarks(series.id) }
        .collectAsStateWithLifecycle(emptyList())
    val episodes = remember(raw) { raw.seriesOrder() }
    val byId = remember(marks) { marks.associateBy { it.id } }
    var season by rememberSaveable(series.id) { mutableStateOf<Int?>(null) }
    var seriesFocus by rememberSaveable(series.id) { mutableStateOf("series-play") }
    var loaded by rememberSaveable(series.id) { mutableStateOf(false) }
    val notice by model.notice.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    LaunchedEffect(series.id) {
        if (!loaded) model.job("Bölüm listesi güncellendi.") {
            model.repo.expand(series)
            loaded = true
        }
    }
    val pages = rememberSaveableStateHolder()
    val currentSeason = season
    if (currentSeason != null) {
        BackHandler { season = null }
        pages.SaveableStateProvider("season-$currentSeason") {
            SeasonPage(model, series, currentSeason, episodes, byId, play, { season = null })
        }
        return
    }
    pages.SaveableStateProvider("series") {
        val info = cinemaInfo(model, series)
        val next = episodes.continueItem(marks)
        val name = next?.let { episodeName(it.title, it.category) }
        val hasHistory = marks.any { it.position > 0 }
        val mark by remember(series.id) { model.repo.library.observeMark(series.id) }
            .collectAsStateWithLifecycle(null)
        val context = LocalContext.current
        val focus = remember { FocusRequester() }
        val seasons = episodes.groupBy { it.seriesEpisode().season }.toSortedMap()
        val state = rememberLazyListState()
        var positioned by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!positioned) {
                positioned = true
                withFrameNanos { }
                if (!seriesFocus.startsWith("season-")) focus.requestFocus()
            }
        }
        Box(Modifier.fillMaxSize().testTag("series-page")) {
            CinemaBackdrop(model, series, info)
            LazyColumn(Modifier.fillMaxSize().testTag("details-content").focusGroup(), state = state,
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(series.title, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
                        MovieMeta(series, info)
                        if (info.summary.isNotBlank()) Text(info.summary, Modifier.widthIn(max = 510.dp),
                            fontSize = 14.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(5.dp)) {
                        item {
                            Button(onClick = {
                                if (next != null) play(next, episodes)
                                else model.job("Bölüm listesi güncellendi.") { model.repo.expand(series); loaded = true }
                            },
                                modifier = Modifier.testTag("series-play").focusRequester(focus)
                                    .onFocusChanged { if (it.isFocused) seriesFocus = "series-play" }) {
                                Text(if (name == null) { if (busy) "Bölümler yükleniyor…" else "Bölümleri yükle" } else if (hasHistory)
                                    "Devam et: S${name.season} B${name.episode}" else "Oynat (S${name.season} B${name.episode})")
                            }
                        }
                        item { Button(onClick = { model.mark(series, false) }) { Text(if (mark?.listed == true) "✓ Listem" else "+ Listem") } }
                        item { Button(onClick = { model.mark(series, true) }) { Text(if (mark?.favorite == true) "Favoriden çıkar" else "Favoriye ekle") } }
                        if (info.trailer.isNotBlank()) item {
                            Button(onClick = {
                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://www.youtube.com/watch?v=${info.trailer}"))) }
                                    .onFailure { model.notice.value = "Fragman için uygun uygulama bulunamadı." }
                            }) { Text("Fragman") }
                        }
                    }
                }
                item { VersionChoice(model, series) }
                item { Text("Sezonlar", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                item {
                    LazyRow(Modifier.testTag("season-shelf").focusGroup(),
                        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        items(seasons.keys.toList(), key = { it }) { number ->
                            val rows = seasons.getValue(number)
                            val count = rows.count { ep -> byId[ep.id]?.let { watched(it.position, it.duration) } == true }
                            val target = remember { FocusRequester() }
                            if (seriesFocus == "season-$number") LaunchedEffect(Unit) { target.requestFocus() }
                            FocusCard(onClick = { season = number }, modifier = Modifier.width(210.dp)
                                .testTag("season-$number").focusRequester(target)
                                .onFocusChanged { if (it.isFocused) seriesFocus = "season-$number" }) {
                                Column {
                                    Poster(model, rows.first().takeIf { it.logo.isNotBlank() } ?: series, Modifier.fillMaxWidth().height(96.dp))
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Sezon $number${if (count == rows.size) " ✓" else ""}", fontSize = 20.sp)
                                        Text("${rows.size} bölüm · $count/${rows.size} izlendi", color = Ink.muted, fontSize = 12.sp)
                                        ProgressLine(count.toFloat() / rows.size)
                                    }
                                }
                            }
                        }
                    }
                }
                if (episodes.isEmpty()) item {
                    Text(if (busy) "Bölümler yükleniyor…" else if (loaded) "Sağlayıcı bölüm bildirmedi." else notice,
                        color = Ink.muted)
                    if (!busy) Button(onClick = { model.job("Bölüm listesi güncellendi.") { model.repo.expand(series); loaded = true } }) { Text("Yeniden dene") }
                }
                if (info.people.isNotEmpty()) item {
                    Text("Oyuncular", fontSize = 20.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(info.people) { person ->
                            FocusCard(onClick = {}, modifier = Modifier.width(110.dp)) {
                                Column(Modifier.padding(10.dp)) {
                                    if (person.portrait.isNotBlank()) coil.compose.AsyncImage(person.portrait, null, Modifier.size(88.dp))
                                    Text(person.name, fontSize = 12.sp, maxLines = 2)
                                    Text(person.role, color = Ink.muted, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                } else if (info.cast.isNotBlank()) item { Text("Oyuncular", fontSize = 20.sp); Text(info.cast, color = Ink.muted) }
                item { Button(onClick = back) { Text("Geri") } }
            }
        }
    }
}

@Composable
private fun SeasonPage(model: LibraryModel, series: Item, season: Int, all: List<Item>, marks: Map<String, Mark>,
                       play: (Item, List<Item>) -> Unit, back: () -> Unit) {
    val episodes = all.filter { it.seriesEpisode().season == season }
    var menu by remember { mutableStateOf<Item?>(null) }
    val list = rememberLazyListState()
    var lastFocus by rememberSaveable { mutableStateOf("") }
    var positioned by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf("") }
    LaunchedEffect(episodes.isNotEmpty()) {
        if (!positioned && episodes.isNotEmpty()) {
            val target = episodes.indexOfFirst { it.id == lastFocus }.coerceAtLeast(0)
            restore = episodes[target].id
            list.scrollToItem(target)
            positioned = true
        }
    }
    fun closeMenu() { restore = menu?.id.orEmpty(); menu = null }
    BackHandler(menu != null) { closeMenu() }
    Box(Modifier.fillMaxSize().testTag("season-page")) {
        CinemaBackdrop(model, series, MovieInfo.from(series), dim = true)
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)
            .focusProperties { if (menu != null) canFocus = false }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(series.title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Sezon $season · ${episodes.size} bölüm", color = Ink.muted, fontSize = 15.sp)
                }
                Button(onClick = back) { Text("Sezonlara dön") }
            }
            LazyColumn(Modifier.weight(1f).testTag("episode-list").focusGroup(), state = list,
                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(episodes, key = { it.id }) { ep ->
                    val mark = marks[ep.id]
                    val duration = mark?.duration ?: 0
                    val position = mark?.position ?: 0
                    val name = episodeName(ep.title, ep.category)
                    val done = watched(position, duration)
                    val focus = remember { FocusRequester() }
                    if (positioned && restore == ep.id && menu == null) LaunchedEffect(restore, positioned, menu) {
                        withFrameNanos { }
                        focus.requestFocus()
                        restore = ""
                    }
                    FocusCard(onClick = { play(ep, all) }, onLongClick = { menu = ep },
                        modifier = Modifier.fillMaxWidth().testTag("episode-${ep.id}").focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) lastFocus = ep.id }
                            .semantics { stateDescription = if (done) "İzlendi" else if (position > 0) "Yarım kaldı" else "İzlenmedi" }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Poster(model, ep, Modifier.width(140.dp).height(90.dp))
                            Column(Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("B${name.episode} · ${name.title}${if (done) " ✓" else ""}", fontSize = 18.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(if (duration > 1) "${duration / 60_000} dk" else "Süre bilgisi yok", color = Ink.muted, fontSize = 12.sp)
                                ProgressLine(if (duration > 0) position.toFloat() / duration else 0f)
                                if (ep.summary.isNotBlank()) Text(ep.summary, fontSize = 12.sp, color = Ink.muted, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        menu?.let { ep ->
            val focus = remember { FocusRequester() }
            LaunchedEffect(ep.id) { focus.requestFocus() }
            GlassPanel(Modifier.align(Alignment.Center).width(400.dp).testTag("episode-actions")) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(episodeName(ep.title).title, fontSize = 20.sp, maxLines = 2)
                    Button(onClick = { model.watched(ep, true); closeMenu() }, modifier = Modifier.focusRequester(focus)) {
                        Text("İzlendi olarak işaretle")
                    }
                    Button(onClick = { model.watched(ep, false); closeMenu() }) { Text("İzlenmedi olarak işaretle") }
                    Button(onClick = { closeMenu() }) { Text("Geri") }
                }
            }
        }
    }
}
