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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dev.lumenara.data.*
import dev.lumenara.design.Ink
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
    Box(Modifier.fillMaxSize().background(Ink.background)) {
        Crossfade(address, animationSpec = tween(100), label = "Sinema arka planı") { art ->
            if (art != null) {
                Box(Modifier.fillMaxSize()) {
                    Artwork(model, item, art.first,
                        Modifier.align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(if (art.second) .62f else 1f),
                        contentScale = if (art.second) ContentScale.Fit else ContentScale.Crop,
                        alignment = Alignment.CenterEnd,
                        role = if (art.second) "poster" else "backdrop",
                    )
                }
            } else
                Image(
                    painterResource(R.drawable.lumenara_coast),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
        }
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Ink.background.copy(alpha = .97f),
                        .30f to Ink.background.copy(alpha = .80f),
                        .62f to Ink.background.copy(alpha = .18f),
                        1f to Ink.background.copy(alpha = .06f),
                    )
                )
        )
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Ink.background.copy(alpha = .28f),
                        .42f to Color.Transparent,
                        .75f to Ink.background.copy(alpha = .88f),
                        1f to Ink.background,
                    )
                )
        )
        if (dim) Box(Modifier.fillMaxSize().background(Ink.background.copy(alpha = .55f)))
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
                    ?.let { "★ ${"%.1f".format(java.util.Locale.US, it)}" }
                    .orEmpty(),
            )
            .filter(String::isNotBlank)
            .joinToString("   ·   ")
    if (text.isNotEmpty())
        Text(
            text,
            color = Ink.accent,
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
        Text(
            when (item.kind) {
                "SERIES" -> "DİZİLERİ KEŞFET"
                "LIVE" -> "ŞİMDİ CANLI"
                else -> "SİNEMA ZAMANI"
            },
            color = Ink.accent,
            letterSpacing = 2.sp,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
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
                color = Ink.text.copy(alpha = .85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            leadingAction()
            Button(
                onClick = { if (item.kind == "SERIES") open(item) else play(item, listOf(item)) },
                colors =
                    ButtonDefaults.colors(
                        containerColor = Ink.focus,
                        contentColor = Ink.background,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Ink.background,
                    ),
            ) {
                NavIcon(2, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Oynat")
            }
            Button(
                onClick = { open(item) },
                colors = ButtonDefaults.colors(containerColor = Ink.surface.copy(alpha = .8f)),
            ) {
                Text("Detaylar")
            }
            Button(
                onClick = { model.mark(item, false) },
                colors = ButtonDefaults.colors(containerColor = Ink.surface.copy(alpha = .8f)),
            ) {
                Text(if (mark?.listed == true) "✓ Listem" else "+ Listem")
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
            color = if (focused) Color.White else Ink.text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (progress && episodeName != null) "S${episodeName.season} B${episodeName.episode}"
            else if (progress) "İzlemeye devam et" else item.year.ifBlank { item.category },
            color = Ink.muted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small outlined icons drawn at a consistent weight; no font-dependent symbol glyphs. */
@Composable
internal fun NavIcon(index: Int, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
            drawPath(
                Path().apply {
                    points.forEachIndexed { i, p ->
                        if (i == 0) moveTo(p.first * u, p.second * u)
                        else lineTo(p.first * u, p.second * u)
                    }
                    if (close) close()
                },
                color,
                style = stroke,
            )
        }
        when (index) {
            1 -> {
                path(3f to 11f, 12f to 3f, 21f to 11f)
                path(
                    6f to 10f,
                    6f to 21f,
                    10f to 21f,
                    10f to 15f,
                    14f to 15f,
                    14f to 21f,
                    18f to 21f,
                    18f to 10f,
                )
            }
            2 -> {
                drawRoundRect(
                    color,
                    Offset(3 * u, 6 * u),
                    androidx.compose.ui.geometry.Size(18 * u, 13 * u),
                    androidx.compose.ui.geometry.CornerRadius(2 * u),
                    style = stroke,
                )
                path(8f to 2f, 12f to 6f, 16f to 2f)
                path(9f to 22f, 15f to 22f)
            }
            3 -> path(8f to 4f, 20f to 12f, 8f to 20f, close = true)
            4 -> {
                drawRoundRect(
                    color,
                    Offset(4 * u, 7 * u),
                    androidx.compose.ui.geometry.Size(16 * u, 14 * u),
                    androidx.compose.ui.geometry.CornerRadius(2 * u),
                    style = stroke,
                )
                path(7f to 3f, 17f to 3f)
            }
            5 -> path(6f to 3f, 18f to 3f, 18f to 22f, 12f to 18f, 6f to 22f, close = true)
            0 -> {
                drawCircle(color, 7 * u, Offset(10 * u, 10 * u), style = stroke)
                path(15f to 15f, 22f to 22f)
            }
            else -> {
                drawCircle(color, 7 * u, center, style = stroke)
                drawCircle(color, 2.5f * u, center, style = stroke)
                for (i in 0..7) {
                    val a = i * Math.PI / 4
                    drawLine(
                        color,
                        center +
                            Offset(
                                kotlin.math.cos(a).toFloat() * 8 * u,
                                kotlin.math.sin(a).toFloat() * 8 * u,
                            ),
                        center +
                            Offset(
                                kotlin.math.cos(a).toFloat() * 11 * u,
                                kotlin.math.sin(a).toFloat() * 11 * u,
                            ),
                        strokeWidth = 1.7f * u,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
