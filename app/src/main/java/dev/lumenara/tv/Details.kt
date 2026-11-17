package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.lumenara.catalog.displayTitle
import dev.lumenara.catalog.productionName
import dev.lumenara.data.*
import dev.lumenara.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun Details(model: LibraryModel, item: Item, play: (Item, List<Item>) -> Unit, back: () -> Unit) {
    if (item.kind == "SERIES") {
        SeriesDetails(model, item, play, back)
        return
    }
    val mark by remember(item.id) { model.repo.library.observeMark(item.id) }
        .collectAsStateWithLifecycle(null)
    val info by produceState(MovieInfo.from(item), item.id) {
        try { value = model.repo.details(item) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { model.notice.value = "Ek film bilgileri alınamadı." }
    }
    Box(Modifier.fillMaxSize()) {
        CinemaBackdrop(model, item, info)
        LazyColumn(Modifier.fillMaxSize().testTag("details-content").focusRestorer().focusGroup(),
            contentPadding = PaddingValues(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal,
                top = 156.dp, bottom = Spacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            item(key = "heading") { DetailHeading(item, info) }
            if (info.summary.isNotBlank()) item(key = "summary") {
                Text(info.summary, Modifier.widthIn(max = 560.dp), style = LumenType.body,
                    color = LumenColors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            item(key = "actions") {
                val primary = remember { FocusRequester() }
                // Lazy content attaches during layout. Keep this effect with the keyed row,
                // so late metadata cannot replace it or steal focus from another action.
                LaunchedEffect(item.id) { withFrameNanos { }; primary.requestFocus() }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit), verticalAlignment = Alignment.CenterVertically) {
                    LumenButton(onClick = { play(item, listOf(item)) }, style = LumenButtonStyle.Secondary,
                        modifier = Modifier.testTag("details-play").focusRequester(primary)) {
                        LumenIcon(LumenIcon.Play, null)
                        Text(if ((mark?.position ?: 0) > 0) "Devam et" else "Oynat")
                    }
                    DetailSecondaryActions(model, item, mark, info)
                }
            }
            item(key = "cast") { DetailCast(info) }
            if (item.kind == "LIVE") item(key = "guide") { GuidePanel(model, item) { replay -> play(replay, listOf(replay)) } }
        }
    }
}

@Composable
internal fun DetailHeading(item: Item, info: MovieInfo) {
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        Text(item.title.displayTitle(), style = LumenType.display, color = LumenColors.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        val minutes = info.minutes.toIntOrNull()
        val duration = if (minutes != null && minutes >= 60) "${minutes / 60} sa ${minutes % 60} dk"
            else info.minutes.takeIf { it.isNotBlank() }?.let { "$it dk" }.orEmpty()
        val metadata = listOf(info.year, info.genre.ifBlank { item.genre }, duration)
            .filter { it.isNotBlank() }.joinToString(" · ")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit), verticalAlignment = Alignment.CenterVertically) {
            if (metadata.isNotEmpty()) Text(metadata, style = LumenType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            info.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { rating ->
                LumenIcon(LumenIcon.Star, null, tint = LumenColors.textSecondary, size = IconSizes.meta)
                Text("%.1f".format(java.util.Locale.US, rating), style = LumenType.meta)
            }
        }
        if (info.collection.isNotBlank()) Text(info.collection, style = LumenType.meta)
    }
}

@Composable
internal fun DetailSecondaryActions(model: LibraryModel, item: Item, mark: Mark?, info: MovieInfo) {
    DetailLibraryActions(model, item, mark)
    DetailVersionPicker(model, item)
    DetailTrailer(model, info)
}

@Composable
private fun DetailLibraryActions(model: LibraryModel, item: Item, mark: Mark?) {
    LumenButton(onClick = { model.mark(item, false) }, style = LumenButtonStyle.Secondary,
        modifier = Modifier.semantics { selected = mark?.listed == true }) {
        LumenIcon(if (mark?.listed == true) LumenIcon.Check else LumenIcon.Plus, null)
        Text("Listem")
    }
    LumenButton(onClick = { model.mark(item, true) }, style = LumenButtonStyle.Secondary,
        modifier = Modifier.semantics {
            contentDescription = if (mark?.favorite == true) "Favoriden çıkar" else "Favoriye ekle"
        }) {
        LumenIcon(LumenIcon.Heart, null)
    }
}

@Composable
internal fun DetailCast(info: MovieInfo) {
    val cast = info.people.joinToString(" · ") { it.name }.ifBlank { info.cast.replace(", ", " · ") }
    if (cast.isNotBlank()) Column(Modifier.widthIn(max = 760.dp).padding(top = Spacing.unit),
        verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        Text("OYUNCULAR", style = LumenType.caption, color = LumenColors.textSecondary)
        Text(cast, style = LumenType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailTrailer(model: LibraryModel, info: MovieInfo) {
    val context = LocalContext.current
    if (info.trailer.isNotBlank()) LumenButton(style = LumenButtonStyle.Secondary, onClick = {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.youtube.com/watch?v=${info.trailer}")))
        }.onFailure { model.notice.value = "Fragman için uygun uygulama bulunamadı." }
    }) { Text("Fragman") }
}

/** D0 has no dropdown primitive; keep this detail-only control beside its action row. */
@Composable
private fun DetailVersionPicker(model: LibraryModel, item: Item) {
    var choices by remember(item.id) { mutableStateOf(emptyList<Item>()) }
    var chosen by remember(item.id) { mutableStateOf("") }
    var expanded by remember(item.id) { mutableStateOf(false) }
    var message by remember(item.id) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    val trigger = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id) {
        try {
            choices = model.repo.versions(item)
            chosen = model.repo.production(model.repo.selectedVersion(item)).id
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { model.notice.value = "Sürüm bilgisi alınamadı." }
    }
    LaunchedEffect(expanded, restore) {
        if (!expanded && restore) { trigger.requestFocus(); restore = false }
    }
    fun dismiss() { if (!saving) { expanded = false; restore = true } }
    BackHandler(expanded) { dismiss() }
    if (choices.size > 1) Box {
        val selected = choices.firstOrNull { it.id == chosen }
        LumenButton(onClick = { expanded = true }, style = LumenButtonStyle.Secondary,
            modifier = Modifier.testTag("version-picker").focusRequester(trigger)) {
            LumenIcon(LumenIcon.Audio, null)
            Text(selected?.title ?: "Sürüm seç")
            LumenIcon(LumenIcon.ChevronDown, null)
        }
        if (expanded) {
            val first = remember { FocusRequester() }
            val offset = with(LocalDensity.current) { 56.dp.roundToPx() }
            Popup(alignment = Alignment.TopStart, offset = IntOffset(0, offset),
                onDismissRequest = { dismiss() }, properties = PopupProperties(focusable = true)) {
                // The popup owns a separate composition; request focus only after its row attaches.
                LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
                Panel(Modifier.width(280.dp).testTag("version-menu")) {
                    Column(Modifier.padding(Spacing.unit).focusGroup()) {
                        choices.forEachIndexed { index, version ->
                            val name = productionName(version.title, version.year).version
                            ListRow(onClick = {
                                saving = true
                                scope.launch {
                                    try {
                                        model.repo.chooseVersion(item, version)
                                        chosen = version.id
                                        expanded = false
                                        restore = true
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { message = "Seçilen sürüm açılamadı." }
                                    finally { saving = false }
                                }
                            }, selected = version.id == chosen, enabled = !saving,
                                modifier = Modifier.testTag("version-${name.name}")
                                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)) {
                                Text("${name.label} - ${version.title}", Modifier.weight(1f), style = LumenType.label)
                                if (version.id == chosen) LumenIcon(LumenIcon.Check, null)
                            }
                        }
                        if (message.isNotBlank()) Row(Modifier.padding(Spacing.unit), verticalAlignment = Alignment.CenterVertically) {
                            LumenIcon(LumenIcon.Info, null, tint = LumenColors.error)
                            Text(message, style = LumenType.meta)
                        }
                    }
                }
            }
        }
    }
}
