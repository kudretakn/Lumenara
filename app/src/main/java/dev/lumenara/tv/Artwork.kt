@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package dev.lumenara.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import dev.lumenara.design.LumenColors
import dev.lumenara.design.LumenIcon
import dev.lumenara.design.LumenType
import dev.lumenara.design.Spacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import coil.intercept.Interceptor
import coil.network.HttpException
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import dev.lumenara.data.Item
import dev.lumenara.data.Transport
import dev.lumenara.data.backgroundWork
import dev.lumenara.data.retainArtworkResult
import coil.decode.DataSource
import coil.request.SuccessResult
import coil.request.CachePolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException

private const val IMAGE_HTTP = "image.allowHttp"
private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/** Sizes are physical pixels. Only TMDB supports server-side resizing. */
internal fun artworkRequest(context: Context, address: String, allowHttp: Boolean,
    width: Int, height: Int, role: String = "poster"): ImageRequest {
    val url = if (address.startsWith("https://image.tmdb.org/t/p/")) {
        val size = when (role) {
            "person" -> "w185"
            "backdrop" -> if (width <= 780) "w780" else "w1280"
            else -> "w342"
        }
        address.replace(Regex("/t/p/[^/]+/"), "/t/p/$size/")
    } else address
    // Permission is part of both cache keys: a permissive redirect must never seed a strict request.
    val cacheKey = "$allowHttp:$url"
    return ImageRequest.Builder(context).data(url).size(width.coerceAtLeast(1), height.coerceAtLeast(1))
        .setParameter(IMAGE_HTTP, allowHttp, memoryCacheKey = null)
        .setHeader(Transport.IMAGE_HTTP_HEADER, allowHttp.toString())
        .diskCacheKey(cacheKey).memoryCacheKey(cacheKey)
        .allowRgb565(true).crossfade(false).build()
}

/** Uses the shared Coil loader, including the fake loader installed by journey tests. */
@Composable
internal fun Artwork(model: LibraryModel, item: Item?, address: String?,
    modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center, role: String = "poster",
    allowHttp: Boolean? = null, width: Int = 0, height: Int = 0) {
    val context = LocalContext.current
    // Legacy detail/logo callers resolve their size after layout, without subcomposition.
    // Grids supply both dimensions and permission once at their parent.
    val permitted = if (allowHttp != null) allowHttp else {
        val sources by model.sources.collectAsStateWithLifecycle()
        sources?.firstOrNull { it.id == item?.source }?.allowHttp == true
    }
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val w = if (width > 0) width else measured.width
    val h = if (height > 0) height else measured.height
    val request = remember(context, address, permitted, w, h, role) {
        address?.takeIf { w > 0 && h > 0 }?.let { artworkRequest(context, it, permitted, w, h, role) }
    }
    var loaded by remember(request) { mutableStateOf(false) }
    val channel = item?.kind == "LIVE"
    Box((if (width > 0 && height > 0) modifier else modifier.onSizeChanged { measured = it })
        .then(if (channel) Modifier.background(LumenColors.surface3) else Modifier)) {
        // Never invent a backdrop or substitute a show's poster for a person's portrait.
        if (!loaded && item != null && role !in listOf("backdrop", "person")) {
            ArtworkFallback(item.title, item.kind, Modifier.matchParentSize())
        }
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            AsyncImage(request, null, Modifier.fillMaxSize(if (channel) .7f else 1f),
                contentScale = if (channel) ContentScale.Fit else contentScale,
                alignment = alignment,
                onSuccess = { loaded = true }, onError = { loaded = false })
        }
    }
}

private val channelTones = listOf(
    Color(0xFF1E2430), Color(0xFF2A1F24), Color(0xFF1F2A24),
    Color(0xFF2B2619), Color(0xFF24202E), Color(0xFF1C2A2B),
)

/** Local artwork treatment; dimensions and focus remain the responsibility of each caller. */
@Composable
private fun ArtworkFallback(title: String, kind: String, modifier: Modifier = Modifier) {
    val channel = kind == "LIVE"
    val tone = remember(title) { channelTones[Math.floorMod(title.hashCode(), channelTones.size)] }
    Box(modifier.background(if (channel) tone else LumenColors.surface2)) {
        if (channel) {
            Text(title.trim().split(Regex("\\s+")).take(2).joinToString(" "),
                Modifier.align(Alignment.Center).fillMaxWidth(.7f),
                style = LumenType.label, color = LumenColors.textPrimary,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            LumenIcon(if (kind == "SERIES" || kind == "EPISODE") LumenIcon.Series else LumenIcon.Film,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.cardGap),
                tint = LumenColors.textPrimary.copy(alpha = .4f))
            Text(title, Modifier.align(Alignment.BottomStart).padding(Spacing.cardGap),
                style = LumenType.cardTitle, color = LumenColors.textPrimary,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A bounded session cache for permanently broken URLs; transient network failures remain retryable. */
internal class ArtworkInterceptor(private val disk: coil.disk.DiskCache,
    private val memory: coil.memory.MemoryCache,
    private val nowMillis: () -> Long = System::currentTimeMillis) : Interceptor {
    private val failures = object : LinkedHashMap<String, Throwable>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Throwable>?) = size > 512
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val address = request.data.toString()
        val allowHttp = request.parameters.value<Boolean>(IMAGE_HTTP) == true
        if (address.startsWith("http://", true) && !allowHttp)
            return ErrorResult(null, request, IOException("Image HTTP blocked"))
        val key = request.diskCacheKey ?: address
        synchronized(failures) { failures[key] }?.let { return ErrorResult(null, request, it) }
        // Let Coil resolve its full memory key and size compatibility first. Memory hits
        // never open the disk cache. Expired disk results are replaced before display.
        val cached = chain.proceed(request)
        val result = retainArtworkResult(cached,
            fromDisk = cached is SuccessResult && cached.dataSource == DataSource.DISK,
            expired = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    disk.openSnapshot(key)?.use {
                        nowMillis() - it.data.toFile().lastModified() > WEEK_MS
                    } == true
                }
            }, reload = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { disk.remove(key) }
                memory.keys.filter { it.key == key }.forEach { memory.remove(it) }
                chain.proceed(request.newBuilder().memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    .diskCachePolicy(CachePolicy.WRITE_ONLY).build())
            })
        if (result is ErrorResult) {
            val error = result.throwable
            if ((error is HttpException && error.response.code in listOf(404, 410)) ||
                (error !is HttpException && error !is IOException && error !is java.util.concurrent.CancellationException)) {
                synchronized(failures) { failures[key] = error }
            }
        }
        return result
    }
}

/** Cache one row beyond the viewport, one image at a time; cancel when the window changes. */
@Composable
internal fun PrefetchArtwork(model: LibraryModel, grid: LazyGridState, rows: List<Item>, columns: Int = 6,
    channelSpan: Int = 1, paged: WindowPages? = null) {
    val context = LocalContext.current
    val sources by model.sources.collectAsStateWithLifecycle()
    LaunchedEffect(grid, rows, sources, columns, channelSpan, paged) {
        model.backgroundReady.first { it }
        Perf.duration("startup_artwork_prefetch_deferred", 0)
        snapshotFlow {
            val visible = grid.layoutInfo.visibleItemsInfo
            val lastRow = visible.filter { it.row >= 0 }.maxOfOrNull { it.row }
            val last = visible.filter { it.row == lastRow }.maxOfOrNull { it.index }
            val size = visible.lastOrNull { it.index == last }?.size
            val count = paged?.count ?: rows.size
            val next = if (last == null) emptyList() else
                (last + 1 until minOf(count, last + 1 + columns)).map { index ->
                    if (paged != null) paged[index] else rows.getOrNull(index)
                }
            size to next
        }.distinctUntilChanged().collectLatest { (size, next) ->
            if (size == null) return@collectLatest
            delay(100)
            var slots = 0
            for (candidate in next) {
                val item = candidate ?: break
                val span = if (item.kind == "LIVE") channelSpan else 1
                if (slots + span > columns) break
                slots += span
                val address = model.repo.poster(item) ?: continue
                val allow = sources?.firstOrNull { it.id == item.source }?.allowHttp == true
                val px = context.resources.displayMetrics.density
                val width = if (item.kind == "LIVE") (28 * px).toInt() else size.width
                val height = if (item.kind == "LIVE") width else (width * 1.5f).toInt()
                backgroundWork.whenIdle {
                    context.imageLoader.execute(artworkRequest(context, address, allow, width, height))
                }
            }
        }
    }
}
