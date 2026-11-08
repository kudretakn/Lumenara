package dev.streamora.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dev.streamora.data.*
import dev.streamora.design.Ink

@Composable
internal fun Details(
    model: LibraryModel,
    item: Item,
    play: (Item, List<Item>) -> Unit,
    back: () -> Unit,
) {
    val mark by
        remember(item.id) { model.repo.library.observeMark(item.id) }
            .collectAsStateWithLifecycle(null)
    val episodes by
        remember(item.id) { model.repo.library.episodes(item.id) }
            .collectAsStateWithLifecycle(emptyList())
    var season by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    val busy by model.busy.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val info by
        produceState(MovieInfo.from(item), item.id) {
            try {
                value = model.repo.details(item)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                model.notice.value = "Ek film bilgileri alınamadı."
            }
        }
    val notice by model.notice.collectAsStateWithLifecycle()
    fun load() =
        model.job("Bölüm listesi güncellendi.") {
            model.repo.expand(item)
            loaded = true
        }
    val primary = remember { FocusRequester() }
    LaunchedEffect(Unit) { primary.requestFocus() }
    Box(Modifier.fillMaxSize()) {
        CinemaBackdrop(model, item, info)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 54.dp, end = 40.dp, top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Button(onClick = back) { Text("Geri") } }
            item {
                Column(
                    Modifier.widthIn(max = 510.dp).padding(top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        item.category.uppercase(java.util.Locale.forLanguageTag("tr")),
                        color = Ink.accent,
                        letterSpacing = 2.sp,
                        fontSize = 11.sp,
                    )
                    Text(
                        item.title,
                        fontSize = 42.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MovieMeta(item, info)
                    if (info.summary.isNotBlank())
                        Text(
                            info.summary,
                            Modifier.widthIn(max = 460.dp),
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = Ink.text.copy(alpha = .85f),
                        )
                    if (info.collection.isNotBlank())
                        Text(info.collection, color = Ink.muted, fontSize = 12.sp)
                }
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(5.dp),
                ) {
                    item {
                        Button(
                            modifier = Modifier.focusRequester(primary),
                            enabled = !busy,
                            onClick = {
                                if (item.kind == "SERIES") load() else play(item, listOf(item))
                            },
                            colors =
                                ButtonDefaults.colors(
                                    containerColor = Ink.focus,
                                    contentColor = Ink.background,
                                ),
                        ) {
                            NavIcon(2, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (item.kind == "SERIES") "Sezon ve bölümler"
                                else if ((mark?.position ?: 0) > 0) "Devam et" else "Oynat"
                            )
                        }
                    }
                    item {
                        Button(onClick = { model.mark(item, false) }) {
                            Text(
                                if (mark?.listed == true) "Listeden çıkar"
                                else "İzleme listeme ekle"
                            )
                        }
                    }
                    item {
                        Button(onClick = { model.mark(item, true) }) {
                            Text(if (mark?.favorite == true) "Favoriden çıkar" else "Favoriye ekle")
                        }
                    }
                    if (info.trailer.isNotEmpty())
                        item {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(
                                                    "https://www.youtube.com/watch?v=" +
                                                        info.trailer
                                                ),
                                            )
                                        )
                                    }
                                        .onFailure {
                                            model.notice.value =
                                                "Fragman için uygun uygulama bulunamadı."
                                        }
                                }
                            ) {
                                Text("Fragmanı izle")
                            }
                        }
                }
            }
            if (info.people.isNotEmpty())
                item {
                    Column(
                        Modifier.padding(top = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Oyuncular", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(22.dp),
                            contentPadding = PaddingValues(4.dp),
                        ) {
                            items(info.people) { person ->
                                var focused by remember { mutableStateOf(false) }
                                Column(
                                    Modifier.width(96.dp)
                                        .onFocusChanged { focused = it.isFocused }
                                        .focusable(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        Modifier.size(88.dp)
                                            .border(
                                                if (focused) 2.dp else 0.dp,
                                                if (focused) Ink.focus
                                                else androidx.compose.ui.graphics.Color.Transparent,
                                                CircleShape,
                                            )
                                            .clip(CircleShape)
                                            .background(Ink.surface),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            person.name.take(1),
                                            fontSize = 26.sp,
                                            color = Ink.accent,
                                        )
                                        if (person.portrait.isNotBlank())
                                            coil.compose.AsyncImage(
                                                person.portrait,
                                                null,
                                                Modifier.fillMaxSize(),
                                                contentScale =
                                                    androidx.compose.ui.layout.ContentScale.Crop,
                                            )
                                    }
                                    Text(
                                        person.name,
                                        Modifier.padding(top = 9.dp),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        person.role,
                                        color = Ink.muted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            else if (info.cast.isNotBlank())
                item {
                    Column(
                        Modifier.padding(top = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Oyuncular", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(info.cast, color = Ink.muted, fontSize = 14.sp)
                    }
                }
            if (loaded && episodes.isEmpty()) item { Text("Sağlayıcı bölüm bildirmedi.") }
            if (item.kind == "LIVE")
                item { GuidePanel(model, item) { replay -> play(replay, listOf(replay)) } }
            if (item.kind == "SERIES" && notice.isNotEmpty())
                item { Text(notice, color = Ink.muted) }
            if (episodes.isNotEmpty())
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(listOf("") + episodes.map { it.category }.distinct(), key = { it }) {
                            value ->
                            Button(onClick = { season = value }) {
                                Text(
                                    (if (value == season) "✓ " else "") +
                                        value.ifEmpty { "Tüm sezonlar" }
                                )
                            }
                        }
                    }
                }
            items(episodes.filter { season == "" || season == it.category }, key = { it.id }) { ep
                ->
                val state by
                    remember(ep.id) { model.repo.library.observeMark(ep.id) }
                        .collectAsStateWithLifecycle(null)
                val percent =
                    state
                        ?.takeIf { it.duration > 0 }
                        ?.let {
                            (it.position.toDouble() / it.duration * 100).toInt().coerceIn(0, 100)
                        } ?: 0
                Button(onClick = { play(ep, episodes) }) {
                    Text(
                        ep.title +
                            when {
                                percent >= 95 -> " · İzlendi"
                                percent > 0 -> " · %$percent"
                                else -> ""
                            }
                    )
                }
                if (ep.summary.isNotEmpty()) Text(ep.summary, color = Ink.muted)
            }
        }
    }
}
