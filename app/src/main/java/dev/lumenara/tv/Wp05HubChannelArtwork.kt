package dev.lumenara.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.lumenara.data.Item
import dev.lumenara.design.*

/** Hub-local equivalent of ChannelLogo, with terminal draw tracking (ChannelCard is another WP). */
@Composable
internal fun Wp05HubChannelArtwork(model: LibraryModel, item: Item, address: String) {
    val context = LocalContext.current
    val sources by model.sources.collectAsStateWithLifecycle()
    val permitted = sources?.firstOrNull { it.id == item.source }?.allowHttp == true
    var size by remember { mutableStateOf(IntSize.Zero) }
    val request = remember(context, address, size, permitted) {
        if (size.width > 0 && size.height > 0)
            artworkRequest(context, address, permitted, (size.width * .7f).toInt(), (size.height * .7f).toInt())
        else null
    }
    var loaded by remember(request) { mutableStateOf(false) }
    var settled by remember(request) { mutableStateOf(false) }
    val tones = listOf(0xFF1E2430, 0xFF2A1F24, 0xFF1F2A24, 0xFF2B2619, 0xFF24202E, 0xFF1C2A2B)
    val tone = Color(tones[Math.floorMod(item.title.hashCode(), tones.size)])
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }
        .clip(RoundedCornerShape(Radii.channelLogo))
        .background(if (loaded) LumenColors.surface3 else tone)
        .wp05HubImageDraw(item.id to request, settled), contentAlignment = Alignment.Center) {
        if (!loaded) Text(item.title.trim().split(Regex("\\s+")).take(2).joinToString(" "),
            Modifier.fillMaxWidth(.85f), style = LumenType.label, maxLines = 2, textAlign = TextAlign.Center)
        if (request != null) AsyncImage(request, null, Modifier.fillMaxSize(.7f), contentScale = ContentScale.Fit,
            onSuccess = { loaded = true; settled = true }, onError = { loaded = false; settled = true })
    }
}
