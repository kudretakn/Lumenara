package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.lumenara.design.GlassPanel
import dev.lumenara.design.Pill
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import dev.lumenara.data.*
import dev.lumenara.design.Ink

private val sections =
    listOf(
        "Ara",
        "Ana Sayfa",
        "Canlı TV",
        "Filmler",
        "Diziler",
        "Listem",
        "Ayarlar",
    )

@Composable
internal fun LibraryScreen(model: LibraryModel = viewModel()) {
    val sources by model.sources.collectAsStateWithLifecycle()
    val available = sources ?: return
    var section by rememberSaveable {
        mutableStateOf(if (available.isEmpty()) "IPTV Kaynakları" else "Ana Sayfa")
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
    val play: (Item, List<Item>) -> Unit = { item, group ->
        siblings = if (item.kind == "EPISODE") group.seriesOrder() else group
        playing = item
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
    var railFocus by remember { mutableStateOf(false) }
    val rail = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val settingsEntry = remember { FocusRequester() }
    var returningToContent by remember { mutableStateOf(false) }
    BackHandler(detail != null || section != "Ana Sayfa") {
        when {
            detail != null -> detail = null
            section == "IPTV Kaynakları" -> {
                returningToContent = true
                section = sourceParent
            }
            else -> {
                returningToContent = true
                section = "Ana Sayfa"
            }
        }
    }
    detail?.let {
        pages.SaveableStateProvider("details-${it.id}") {
            key(it.id) { Details(model, it, play) { detail = null } }
        }
        return
    }
    val width by animateDpAsState(if (railFocus) 240.dp else 72.dp, label = "Menü genişliği")
    val availableUpdate by model.update.collectAsStateWithLifecycle()
    var featured by remember(scope) { mutableStateOf<Item?>(null) }
    val info = cinemaInfo(model, featured)
    var homeInteraction by remember { mutableLongStateOf(0L) }
    LaunchedEffect(section) {
        featured = null
        // Removing a focused page can temporarily move focus to the rail. Back is an
        // explicit return to content, so that transient focus must not suppress it.
        if ((returningToContent || !railFocus) && section != "IPTV Kaynakları") {
            if (returningToContent && section == "Ayarlar") settingsEntry.requestFocus()
            else contentFocus.requestFocus()
        }
        returningToContent = false
    }
    Box(Modifier.fillMaxSize().background(Ink.background).onPreviewKeyEvent {
        if (section == "Ana Sayfa" && it.type == KeyEventType.KeyDown) homeInteraction++
        false
    }) {
        CinemaBackdrop(
            model,
            featured,
            info,
            dim = section !in listOf("Ana Sayfa", "IPTV Kaynakları"),
        )
        Column(Modifier.fillMaxSize().padding(start = 96.dp, end = 48.dp, top = 27.dp, bottom = 27.dp)) {
            if (section != "Ana Sayfa") {
                if (section == "IPTV Kaynakları") {
                    LumenaraLogo(showName = true)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    if (section == "IPTV Kaynakları" && available.isEmpty())
                        "Her hikâye burada başlar."
                    else section,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
            }
            pages.SaveableStateProvider(section) {
                Column(
                    Modifier.fillMaxSize()
                        .semantics { testTag = "section-$section" }
                        .focusRequester(contentFocus)
                        .focusRestorer()
                        .focusGroup()
                ) {
                    when (section) {
                        "IPTV Kaynakları" -> Setup(model, available) { section = "Ana Sayfa" }
                        "Ayarlar" -> SettingsScreen(model, available, settingsEntry) {
                            sourceParent = "Ayarlar"
                            section = "IPTV Kaynakları"
                        }
                        else ->
                            if (available.isEmpty()) {
                                Column(
                                    Modifier.fillMaxWidth(.60f).padding(top = 64.dp),
                                    verticalArrangement = Arrangement.spacedBy(18.dp),
                                ) {
                                    LumenaraLogo(showName = true)
                                    Text(
                                        "Kendi sinemanıza\nhoş geldiniz.",
                                        fontSize = 42.sp,
                                        lineHeight = 46.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "Filmleriniz, dizileriniz ve canlı yayınlarınız.\nBaşlamak için IPTV kaynağınızı ekleyin.",
                                        color = Ink.muted,
                                        fontSize = 15.sp,
                                    )
                                    Button(onClick = {
                                        sourceParent = "Ana Sayfa"
                                        section = "IPTV Kaynakları"
                                    }) {
                                        Text("Kaynak ekle")
                                    }
                                }
                            } else {
                                if (available.size > 1)
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        contentPadding = PaddingValues(vertical = 8.dp),
                                    ) {
                                        items(
                                            listOf("" to "Tümü") + available.map { it.id to it.name },
                                            key = { it.first },
                                        ) { (id, name) ->
                                            Pill(name, scope == id, onClick = { scope = id })
                                        }
                                    }
                                if (section == "Ana Sayfa")
                                    Home(model, scope, open, play, info, { featured = it }, homeInteraction)
                                else Browse(model, section, scope, open, play,
                                    returnToChannel, { returnToChannel = null })
                            }
                    }
                }
            }
        }
        if (railFocus)
            Box(Modifier.fillMaxSize().background(Ink.background.copy(alpha = .55f)))
        GlassPanel(
            modifier = Modifier.width(width).fillMaxHeight()
                .onFocusChanged { railFocus = it.hasFocus }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                        contentFocus.requestFocus()
                        true
                    } else false
                }
                .focusGroup()
                .semantics { testTag = if (railFocus) "nav-open" else "nav-closed" },
            shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
            backdrop = if (railFocus) ({ CinemaBackdrop(model, featured, info, dim = true) }) else null,
        ) {
            Box(Modifier.fillMaxSize()) {
                LumenaraLogo(
                    showName = railFocus,
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 27.dp),
                )
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 27.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    sections.forEachIndexed { index, name ->
                        val selected = name == section || (name == "Ayarlar" && section == "IPTV Kaynakları")
                        Surface(
                            onClick = {
                                section = name
                                model.notice.value = ""
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                .then(if (selected) Modifier.focusRequester(rail) else Modifier)
                                .semantics {
                                    contentDescription = name
                                    if (name == "Ayarlar" && availableUpdate != null)
                                        stateDescription = "Güncelleme hazır"
                                },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(BorderStroke(2.dp, Ink.focusRing), shape = RoundedCornerShape(10.dp))
                            ),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (selected) Ink.glass else Color.Transparent,
                                contentColor = if (selected) Ink.text else Ink.muted,
                                focusedContainerColor = Ink.focus,
                                focusedContentColor = Ink.background,
                            ),
                        ) {
                            if (selected)
                                Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(24.dp).background(Ink.accent))
                            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    NavIcon(index, Modifier.size(20.dp))
                                    if (name == "Ayarlar" && availableUpdate != null)
                                        Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(Ink.focusRing, RoundedCornerShape(50)))
                                }
                                if (railFocus) {
                                    Spacer(Modifier.width(12.dp))
                                    Text(name, fontSize = 15.sp, maxLines = 1)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
    BackHandler(railFocus) { contentFocus.requestFocus() }
}
