package dev.streamora.tv

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
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import dev.streamora.data.*
import dev.streamora.design.Ink

private val sections =
    listOf(
        "Ana Sayfa",
        "Canlı TV",
        "Filmler",
        "Diziler",
        "Favoriler",
        "İzleme Listem",
        "Arama",
        "IPTV Kaynakları",
        "Ayarlar",
    )

@Composable
internal fun LibraryScreen(model: LibraryModel = viewModel()) {
    val sources by model.sources.collectAsStateWithLifecycle()
    val available = sources ?: return
    var section by rememberSaveable {
        mutableStateOf(if (available.isEmpty()) "IPTV Kaynakları" else "Ana Sayfa")
    }
    var scope by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(available) { if (available.none { it.id == scope }) scope = "" }
    var detail by remember { mutableStateOf<Item?>(null) }
    var playing by remember { mutableStateOf<Item?>(null) }
    var siblings by remember { mutableStateOf(emptyList<Item>()) }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val request by model.remoteSearch.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        if (request > 0) {
            playing = null
            detail = null
            section = "Arama"
        }
    }
    val open: (Item) -> Unit = { detail = it }
    val play: (Item, List<Item>) -> Unit = { item, group ->
        siblings = group
        playing = item
    }
    val item = playing
    if (item != null) {
        key(item.id, item.encryptedUrl) {
            Playback(model, item, siblings, { playing = it }, { playing = null }, { siblings = it })
        }
        return
    }
    BackHandler(detail != null || section != "Ana Sayfa") {
        if (detail != null) detail = null else section = "Ana Sayfa"
    }
    detail?.let {
        key(it.id) { Details(model, it, play) { detail = null } }
        return
    }
    var railFocus by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (railFocus) 174.dp else 58.dp)
    val rail = remember { FocusRequester() }
    var featured by remember(scope) { mutableStateOf<Item?>(null) }
    LaunchedEffect(section) { featured = null }
    val info = cinemaInfo(model, featured)
    LaunchedEffect(Unit) { if (section != "IPTV Kaynakları") rail.requestFocus() }
    Box(Modifier.fillMaxSize().background(Ink.background)) {
        CinemaBackdrop(
            model,
            featured,
            info,
            dim = section !in listOf("Ana Sayfa", "IPTV Kaynakları"),
        )
        Column(Modifier.fillMaxSize().padding(start = 82.dp, end = 28.dp, top = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "STREAMORA",
                    color = Ink.text,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.width(24.dp))
                Text(section, color = Ink.muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                val availableUpdate by model.update.collectAsStateWithLifecycle()
                if (availableUpdate != null && section != "Ayarlar")
                    Button(onClick = { section = "Ayarlar" }) { Text("Güncelleme hazır") }
            }
            if (section != "Ana Sayfa") {
                Spacer(Modifier.height(24.dp))
                Text(
                    if (section == "IPTV Kaynakları" && available.isEmpty())
                        "Her hikâye burada başlar."
                    else section,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
            }
            pages.SaveableStateProvider(section) {
                when (section) {
                    "IPTV Kaynakları" -> Setup(model, available) { section = "Ana Sayfa" }
                    "Ayarlar" -> SettingsScreen(model, available)
                    else ->
                        if (available.isEmpty()) {
                            Column(
                                Modifier.fillMaxWidth(.60f).padding(top = 100.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
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
                                Button(onClick = { section = "IPTV Kaynakları" }) {
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
                                        Button(onClick = { scope = id }) {
                                            Text(if (scope == id) "✓ $name" else name)
                                        }
                                    }
                                }
                            if (section == "Ana Sayfa")
                                Home(model, scope, open, play, info, { featured = it }) {
                                    section = "Canlı TV"
                                }
                            else Browse(model, section, scope, open, play)
                        }
                }
            }
        }
        Column(
            Modifier.width(width)
                .fillMaxHeight()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(
                            Ink.background,
                            Ink.background.copy(alpha = if (railFocus) .96f else .7f),
                        )
                    )
                )
                .onFocusChanged { railFocus = it.hasFocus }
                .focusGroup()
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            sections.forEachIndexed { index, name ->
                Surface(
                    onClick = {
                        section = name
                        model.notice.value = ""
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(39.dp)
                            .then(if (name == section) Modifier.focusRequester(rail) else Modifier)
                            .semantics { contentDescription = name },
                    shape =
                        ClickableSurfaceDefaults.shape(
                            androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors =
                        ClickableSurfaceDefaults.colors(
                            containerColor =
                                if (name == section) Ink.surface.copy(alpha = .65f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (name == section) Ink.text else Ink.muted,
                            focusedContainerColor = Ink.focus,
                            focusedContentColor = Ink.background,
                        ),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NavIcon(index, Modifier.size(20.dp))
                        if (railFocus) {
                            Spacer(Modifier.width(12.dp))
                            Text(name, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
