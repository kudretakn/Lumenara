package dev.streamora.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.compose.AsyncImage
import dev.streamora.data.*
import dev.streamora.design.Ink
import dev.streamora.design.Pill

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
    source: String,
    kind: String,
    shelf: String,
    open: (Item) -> Unit,
    focus: (Item) -> Unit,
) {
    val rows by
        remember(source, kind, shelf, title) {
                when (shelf) {
                    "collection" -> model.repo.library.members(source, title)
                    "suggest" -> model.repo.library.suggestions(source)
                    else -> model.repo.library.browse(source, kind, "", "", shelf, 24)
                }
            }
            .collectAsStateWithLifecycle(emptyList())
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            title,
            fontSize = 19.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier.focusRestorer().focusGroup(),
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
    info: MovieInfo,
    feature: (Item?) -> Unit,
    live: () -> Unit,
) {
    val collections by
        remember(source) { model.repo.library.collections(source) }
            .collectAsStateWithLifecycle(emptyList())
    val candidates by
        remember(source) { model.repo.library.browse(source, "", "", "", "", 24) }
            .collectAsStateWithLifecycle(emptyList())
    var focusedId by rememberSaveable(source) { mutableStateOf<String?>(null) }
    var focused by remember(source) { mutableStateOf<Item?>(null) }
    val first =
        candidates.firstOrNull { it.id == focusedId }
            ?: candidates.firstOrNull { it.kind == "MOVIE" || it.kind == "SERIES" }
            ?: candidates.firstOrNull()
    val hero = focused ?: first
    LaunchedEffect(hero) { feature(hero) }
    val choose: (Item) -> Unit = {
        focused = it
        focusedId = it.id
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * .49f).coerceAtLeast(230.dp)
        Column {
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                hero?.let {
                    Hero(
                        model,
                        it,
                        info,
                        open,
                        play,
                        Modifier.align(Alignment.CenterStart).padding(top = 12.dp),
                    )
                }
            }
            LazyColumn(
                Modifier.weight(1f).focusRestorer().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            ) {
                item {
                    Shelf(model, "Kaldığın yerden devam et", source, "", "resume", open, choose)
                }
                item {
                    Shelf(model, "Son eklenen filmler", source, "MOVIE", "recent", open, choose)
                }
                item {
                    Shelf(model, "Yüksek puanlı filmler", source, "MOVIE", "popular", open, choose)
                }
                item { Shelf(model, "Diziler", source, "SERIES", "", open, choose) }
                item { Shelf(model, "Favori kanallar", source, "LIVE", "favorite", open, choose) }
                item { Shelf(model, "İzleme listem", source, "", "list", open, choose) }
                items(collections, key = { it }) { name ->
                    Shelf(model, name, source, "MOVIE", "collection", open, choose)
                }
                item { Shelf(model, "İzlediklerinize göre", source, "", "suggest", open, choose) }
                item { Button(onClick = live) { Text("Canlı TV’ye git") } }
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
) {
    val kind =
        when (section) {
            "Canlı TV" -> "LIVE"
            "Filmler" -> "MOVIE"
            "Diziler" -> "SERIES"
            else -> ""
        }
    var timeline by rememberSaveable { mutableStateOf(false) }
    if (kind == "LIVE") {
        if (timeline) {
            Timeline(model, source, open, play) { timeline = false }
            return
        }
        Button(onClick = { timeline = true }) { Text("Yayın çizelgesi") }
    }
    var listTab by rememberSaveable { mutableStateOf("Favoriler") }
    if (section == "Listem") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Favoriler", "İzleme Listem").forEach { tab ->
                Pill(tab, listTab == tab, onClick = { listTab = tab })
            }
        }
    }
    val shelf =
        when (if (section == "Listem") listTab else section) {
            "Favoriler" -> "favorite"
            "İzleme Listem" -> "list"
            else -> ""
        }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable(source) { mutableStateOf("") }
    var limit by rememberSaveable(source, query, category) { mutableIntStateOf(80) }
    var previewId by rememberSaveable(source, category) { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    BackHandler(advanced) { advanced = false }
    var year by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(
        section,
        source,
        kind,
        shelf,
        query,
        category,
        limit,
        year,
        genre,
        language,
        rating,
    ) {
        model.filter.value =
            Filter(
                source,
                kind,
                category,
                query,
                shelf,
                limit,
                year,
                genre,
                language,
                rating.toDouble(),
            )
    }
    val rows by model.results.collectAsStateWithLifecycle()
    val preview = rows.firstOrNull { it.id == previewId }
    val groups by
        remember(kind, source) { model.repo.library.categories(kind, source) }
            .collectAsStateWithLifecycle(emptyList())
    val voice =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK)
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.let { query = it }
        }
    if (kind == "MOVIE" || kind == "SERIES") {
        Button(onClick = { advanced = !advanced }) { Text("İçerik filtreleri") }
        if (advanced)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) { Field("Yıl", year, { year = it.take(4) }) }
                Column(Modifier.weight(1f)) { Field("Tür", genre, { genre = it }) }
                Column(Modifier.weight(1f)) { Field("Dil", language, { language = it }) }
                Button(onClick = { rating = (rating + 1) % 10 }) { Text("Puan ≥ $rating") }
            }
    }
    if (section == "Ara") {
        Field("Kanal adı, kategori veya yayın kimliği", query, { query = it })
        fun listen() {
            runCatching {
                voice.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                )
            }
                .onFailure {
                    model.notice.value = "Ses tanıma hizmeti bulunamadı. Metinle arayabilirsiniz."
                }
        }
        val request by model.remoteSearch.collectAsStateWithLifecycle()
        LaunchedEffect(request) {
            if (request > 0) {
                model.remoteSearch.value = 0
                listen()
            }
        }
        Button(onClick = { listen() }) { Text("Sesle ara") }
        val notice by model.notice.collectAsStateWithLifecycle()
        if (notice.isNotEmpty()) Text(notice, color = Ink.accent)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (groups.isNotEmpty())
            LazyColumn(Modifier.width(122.dp).focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(listOf("") + groups, key = { it }) { group ->
                    Surface(onClick = { category = group }) {
                        Text(
                            (if (group == category) "✓ " else "") + group.ifEmpty { "Tümü" },
                            Modifier.fillMaxWidth().padding(6.dp),
                            maxLines = 2,
                        )
                    }
                }
            }
        if (section != "Canlı TV") {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(116.dp),
                modifier = Modifier.weight(1f).focusRestorer().focusGroup(),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (rows.isEmpty()) item { Text("Eşleşen içerik yok.", color = Ink.muted) }
                items(rows.size, key = { rows[it].id }) { index ->
                    MovieCard(
                        model,
                        rows[index],
                        rows[index].kind != "LIVE",
                        open,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (rows.size >= limit)
                    item { Button(onClick = { limit += 80 }) { Text("Devamını göster") } }
            }
        } else
            LazyColumn(Modifier.weight(1f).focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rows.isEmpty()) item { Text("Eşleşen içerik yok.", color = Ink.muted) }
                items(rows, key = { it.id }) { item ->
                    Surface(
                        onClick = { if (section == "Canlı TV") previewId = item.id else open(item) }
                    ) {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(item.title, maxLines = 2)
                            Text(item.category, color = Ink.accent, fontSize = 12.sp)
                        }
                    }
                }
                if (rows.size >= limit)
                    item { Button(onClick = { limit += 80 }) { Text("Devamını göster") } }
            }
        if (section == "Canlı TV")
            Column(Modifier.weight(1f)) {
                preview?.let { item ->
                    key(item.id) {
                        Video(
                            model,
                            item,
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            preview = true,
                        )
                    }
                    Text(item.title, fontSize = 19.sp)
                    Button(onClick = { play(item, rows) }) { Text("Tam ekran") }
                    Button(onClick = { open(item) }) { Text("Kanal işlemleri") }
                } ?: Text("Önizleme için bir kanal seçin.", color = Ink.muted)
            }
    }
}
