package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.semantics.*
import dev.lumenara.design.Pill
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import dev.lumenara.data.*
import dev.lumenara.design.Ink
import kotlinx.coroutines.launch

@Composable
internal fun LibraryScreen(model: LibraryModel = viewModel()) {
    val sources by model.sources.collectAsStateWithLifecycle()
    val startInSetup by model.startInSetup.collectAsStateWithLifecycle()
    val available = sources ?: return
    val initialSetup = startInSetup ?: return
    var section by rememberSaveable {
        mutableStateOf(if (initialSetup) "IPTV Kaynakları" else "Ana Sayfa")
    }
    var sourceParent by rememberSaveable { mutableStateOf("Ana Sayfa") }
    var scope by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(available) { if (available.none { it.id == scope }) scope = "" }
    var detail by remember { mutableStateOf<Item?>(null) }
    var playing by remember { mutableStateOf<Item?>(null) }
    var returnToChannel by remember { mutableStateOf<String?>(null) }
    var siblings by remember { mutableStateOf(emptyList<Item>()) }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val request by model.remoteSearch.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        if (request > 0) {
            playing = null
            detail = null
            section = "Ara"
        }
    }
    val open: (Item) -> Unit = { detail = it }
    val playbackScope = rememberCoroutineScope()
    val play: (Item, List<Item>) -> Unit = { item, group ->
        playbackScope.launch {
            try {
                val chosen = model.repo.selectedVersion(item)
                siblings = if (chosen.kind == "EPISODE" && chosen.parent != item.parent)
                    model.repo.episodeSnapshot(chosen.parent).seriesOrder()
                    else if (item.kind == "EPISODE") group.seriesOrder() else group
                playing = chosen
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { model.notice.value = "Seçilen sürüm açılamadı." }
        }
    }
    val item = playing
    if (item != null) {
        key(item.id, item.encryptedUrl) {
            Playback(model, item, siblings, { playing = it }, {
                if (section == "Canlı TV") returnToChannel = item.id
                playing = null
            }, { siblings = it })
        }
        return
    }
    val settingsEntry = remember { FocusRequester() }
    BackHandler(detail != null || section != "Ana Sayfa") {
        when {
            detail != null -> detail = null
            section == "IPTV Kaynakları" -> section = sourceParent
            else -> section = "Ana Sayfa"
        }
    }
    detail?.let {
        pages.SaveableStateProvider("details-${it.id}") {
            key(it.id) { Details(model, it, play) { detail = null } }
        }
        return
    }
    var hubCard by rememberSaveable { mutableStateOf("Canlı TV") }
    val entry = remember { FocusRequester() }
    LaunchedEffect(section) {
        if (section == "IPTV Kaynakları") {
            withFrameNanos { }
            entry.requestFocus()
        }
    }
    Box(Modifier.fillMaxSize().background(Ink.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            pages.SaveableStateProvider(section) {
                Column(Modifier.fillMaxSize().semantics { testTag = "section-$section" }
                    .focusRequester(entry).focusRestorer().focusGroup()) {
                    when (section) {
                        "IPTV Kaynakları" -> {
                            LumenaraLogo(showName = true)
                            Text("IPTV Kaynakları", fontSize = 28.sp)
                            Setup(model, available) { section = "Ana Sayfa" }
                        }
                        "Ayarlar" -> {
                            Text("Ayarlar", fontSize = 28.sp)
                            Spacer(Modifier.height(14.dp))
                            SettingsScreen(model, available, settingsEntry) {
                                sourceParent = "Ayarlar"
                                section = "IPTV Kaynakları"
                            }
                        }
                        "Ana Sayfa" -> {
                            if (available.size > 1) LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            ) {
                                items(listOf("" to "Tümü") + available.map { it.id to it.name },
                                    key = { it.first }) { (id, name) ->
                                    Pill(name, scope == id, onClick = { scope = id })
                                }
                            }
                            Home(model, scope, open, play, hubCard) {
                                hubCard = it
                                section = it
                                model.notice.value = ""
                            }
                        }
                        else -> Browse(model, section, scope, open, play,
                            returnToChannel, { returnToChannel = null })
                    }
                }
            }
        }
        key(section) {
            DoubleBackExit(section == "Ana Sayfa" ||
                available.isEmpty() && section == "IPTV Kaynakları" && sourceParent == "Ana Sayfa")
        }
    }
}
