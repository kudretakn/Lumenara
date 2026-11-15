package dev.lumenara.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dev.lumenara.data.*
import dev.lumenara.design.LumenColors
import dev.lumenara.design.LumenBrushes
import dev.lumenara.design.Motion
import dev.lumenara.design.LumenIcon
import dev.lumenara.design.LumenButton
import dev.lumenara.design.LumenButtonStyle
import dev.lumenara.design.FocusCard
import dev.lumenara.design.ProgressLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun cinemaInfo(model: LibraryModel, item: Item?): MovieInfo {
    if (item == null) return MovieInfo()
    return key(item.id) {
        val info by
            produceState(MovieInfo.from(item), item) {
                delay(
                    350
                ) // Avoid metadata requests while the remote is moving quickly through a shelf.
                try {
                    value = model.repo.details(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    /* Provider metadata remains available offline. */
                }
            }
        info
    }
}

@Composable
internal fun CinemaBackdrop(
    model: LibraryModel,
    item: Item?,
    info: MovieInfo,
    dim: Boolean = false,
) {
    val address by
        produceState<Pair<String, Boolean>?>(null, item?.id, item?.backdrop, info.backdrop) {
            value = item?.let {
                val wide = model.repo.backdrop(it) ?: info.backdrop.takeIf(String::isNotBlank)
                if (wide != null) wide to false
                else model.repo.poster(it)?.let { poster -> poster to true }
            }
        }
    Box(Modifier.fillMaxSize().background(LumenColors.bg)) {
        Crossfade(address, animationSpec = tween(Motion.backdropCrossfadeMillis), label = "Sinema arka planı") { art ->
            if (art != null) {
                Box(Modifier.fillMaxSize()) {
                    Artwork(model, item, art.first,
                        if (art.second) Modifier.align(Alignment.CenterEnd).padding(end = 48.dp)
                            .height(360.dp).aspectRatio(2f / 3f)
                        else Modifier.fillMaxSize(),
                        contentScale = if (art.second) ContentScale.Fit else ContentScale.Crop,
                        alignment = Alignment.CenterEnd,
                        role = if (art.second) "poster" else "backdrop",
                    )
                }
            }
        }
        Box(Modifier.fillMaxHeight().fillMaxWidth(.68f).background(LumenBrushes.detailLeft))
        Box(Modifier.fillMaxSize().background(LumenBrushes.detailBottom))
        if (dim) Box(Modifier.fillMaxSize().background(LumenColors.bg.copy(alpha = .35f)))
    }
}

@Composable
internal fun MovieMeta(item: Item, info: MovieInfo = MovieInfo.from(item)) {
    val text =
        listOf(
                info.year,
                info.genre.ifBlank { item.category },
                info.minutes.takeIf(String::isNotBlank)?.let { "$it dk" }.orEmpty(),
                info.rating
                    .toDoubleOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { "Puan ${"%.1f".format(java.util.Locale.US, it)}" }
                    .orEmpty(),
            )
            .filter(String::isNotBlank)
            .joinToString("   ·   ")
    if (text.isNotEmpty())
        Text(
            text,
            color = LumenColors.accent,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
}

@Composable
internal fun Hero(
    model: LibraryModel,
    item: Item,
    info: MovieInfo,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
    modifier: Modifier = Modifier,
    leadingAction: @Composable () -> Unit = {},
) {
    val mark by
        remember(item.id) { model.repo.library.observeMark(item.id) }
            .collectAsStateWithLifecycle(null)
    Column(modifier.widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Keep action buttons in place while the title and shared backdrop crossfade.
        Crossfade(item.title, animationSpec = tween(600), label = "Vitrin başlığı") { title ->
            Text(
                title,
                Modifier.widthIn(max = 500.dp),
                fontSize = 38.sp,
                lineHeight = 41.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MovieMeta(item, info)
        if (info.summary.isNotBlank())
            Text(
                info.summary,
                Modifier.widthIn(max = 420.dp),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = LumenColors.textPrimary.copy(alpha = .85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            leadingAction()
            LumenButton(
                onClick = { if (item.kind == "SERIES") open(item) else play(item, listOf(item)) },
            ) {
                LumenIcon(LumenIcon.Play, contentDescription = null, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text("Oynat")
            }
            LumenButton(
                onClick = { open(item) },
                style = LumenButtonStyle.Secondary,
            ) {
                Text("Detaylar")
            }
            LumenButton(
                onClick = { model.mark(item, false) },
                style = LumenButtonStyle.Secondary,
            ) {
                LumenIcon(if (mark?.listed == true) LumenIcon.Check else LumenIcon.Plus, contentDescription = null)
                Text("Listem")
            }
        }
    }
}

@Composable
internal fun MovieCard(
    model: LibraryModel,
    item: Item,
    portrait: Boolean,
    open: (Item) -> Unit,
    focus: (Item) -> Unit = {},
    modifier: Modifier = Modifier,
    progress: Boolean = false,
    mark: Mark? = null,
    artworkHttp: Boolean? = null,
    artworkWidth: Int = 0,
    artworkHeight: Int = 0,
) {
    var focused by remember { mutableStateOf(false) }
    var restoreFocus by rememberSaveable(item.id) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (restoreFocus) { withFrameNanos { }; requester.requestFocus(); restoreFocus = false } }
    val episodes by remember(item.id, progress) {
        if (progress && item.kind == "SERIES") model.repo.episodes(item.id)
        else kotlinx.coroutines.flow.flowOf(emptyList<Item>())
    }.collectAsStateWithLifecycle(emptyList())
    val marks by remember(item.id, progress) {
        if (progress && item.kind == "SERIES") model.repo.library.episodeMarks(item.id)
        else kotlinx.coroutines.flow.flowOf(emptyList<Mark>())
    }.collectAsStateWithLifecycle(emptyList())
    val continuing = episodes.continueItem(marks)
    val episodeMark = marks.firstOrNull { it.id == continuing?.id }
    val episodeName = continuing?.let { dev.lumenara.catalog.episodeName(it.title, it.category) }
    val observedMark by remember(item.id, progress) {
        if (progress && item.kind != "SERIES") model.repo.library.observeMark(item.id)
        else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(null)
    Column(modifier.width(if (portrait) 116.dp else 194.dp)) {
        FocusCard(
            onClick = { restoreFocus = true; open(item) },
            modifier =
                Modifier.fillMaxWidth().focusRequester(requester)
                    .semantics { contentDescription = item.title }
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) focus(item)
                    },
        ) {
            Box {
                Poster(
                    model,
                    item,
                    Modifier.fillMaxWidth().height(if (portrait) 150.dp else 108.dp),
                    allowHttp = artworkHttp, width = artworkWidth, height = artworkHeight,
                )
                val progressMark = if (item.kind == "SERIES") episodeMark else mark ?: observedMark
                if (progress && (progressMark?.duration ?: 0) > 0) {
                    val ratio =
                        ((progressMark?.position ?: 0).toFloat() / checkNotNull(progressMark).duration).coerceIn(
                            0f,
                            1f,
                        )
                    ProgressLine(ratio, Modifier.align(Alignment.BottomStart))
                }
            }
        }
        Text(
            item.title,
            Modifier.padding(top = 7.dp),
            color = if (focused) Color.White else LumenColors.textPrimary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (progress && episodeName != null) "S${episodeName.season} B${episodeName.episode}"
            else if (progress) "İzlemeye devam et" else item.year.ifBlank { item.category },
            color = LumenColors.textSecondary,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
