package dev.lumenara.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import dev.lumenara.data.*
import dev.lumenara.design.LumenColors
import dev.lumenara.design.LumenBrushes
import dev.lumenara.design.Motion
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
                    // Scrims shade artwork only. Over a bare bg they add no visual
                    // value and GPU gradient dithering can alter the neutral colour.
                    Box(Modifier.fillMaxHeight().fillMaxWidth(.68f).background(LumenBrushes.detailLeft))
                    Box(Modifier.fillMaxSize().background(LumenBrushes.detailBottom))
                    if (dim) Box(Modifier.fillMaxSize().background(LumenColors.bg.copy(alpha = .35f)))
                }
            }
        }
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
