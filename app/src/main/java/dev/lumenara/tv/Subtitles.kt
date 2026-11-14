package dev.lumenara.tv

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.media3.common.*
import androidx.tv.material3.*
import dev.lumenara.design.Ink
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.focusGroup
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import dev.lumenara.catalog.SubtitleType
import dev.lumenara.catalog.productionName
import dev.lumenara.catalog.languageName
import dev.lumenara.data.*
import dev.lumenara.design.GlassPanel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
internal fun Subtitles(attach: (MediaItem.SubtitleConfiguration?) -> Unit) {
    var editor by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val formats =
        listOf(
            "SRT" to MimeTypes.APPLICATION_SUBRIP,
            "ASS" to MimeTypes.TEXT_SSA,
            "VTT" to MimeTypes.TEXT_VTT,
        )
    var format by remember { mutableIntStateOf(0) }
    fun add(uri: Uri) =
        attach(
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(formats[format].second)
                .setLanguage("tr")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        )
    val file =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::add)
        }
    Button(onClick = { editor = !editor }) { Text("Harici altyazı") }
    if (editor)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { format = (format + 1) % formats.size }) {
                Text("Biçim · ${formats[format].first}")
            }
            Button(
                onClick = {
                    runCatching { file.launch(arrayOf("*/*")) }
                        .onFailure { message = "Dosya seçici bulunamadı." }
                }
            ) {
                Text("Dosya seç")
            }
            Field("Altyazı adresi", address, { address = it })
            Button(
                onClick = {
                    val uri = Uri.parse(address.trim())
                    if (uri.scheme !in listOf("https", "http") || uri.host == null)
                        message = "Geçerli bir web adresi girin."
                    else add(uri)
                }
            ) {
                Text("Adresten ekle")
            }
            Button(onClick = { attach(null) }) { Text("Harici altyazıyı kaldır") }
            if (message.isNotBlank()) Text(message, color = Ink.accent)
        }
}

@Composable
internal fun VersionChoice(model: LibraryModel, item: Item,
                           selected: (Item) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var choices by remember(item.id) { mutableStateOf(emptyList<Item>()) }
    var chosen by remember(item.id) { mutableStateOf("") }
    var message by remember(item.id) { mutableStateOf("") }
    LaunchedEffect(item.id) {
        try {
            choices = model.repo.versions(item)
            chosen = model.repo.production(model.repo.selectedVersion(item)).id
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Sürüm bilgisi alınamadı." }
    }
    if (choices.size > 1) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Dublaj / Altyazılı")
        for (version in choices.distinctBy { productionName(it.title, it.year).version }) {
            Button(onClick = {
                scope.launch {
                    try {
                        val resolved = model.repo.chooseVersion(item, version)
                        chosen = version.id
                        selected(resolved)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Bu bölüm seçilen sürümde bulunamadı." }
                }
            }, modifier = Modifier.testTag("version-${productionName(version.title, version.year).version.name}")) {
                Text((if (chosen == version.id) "✓ " else "") + productionName(version.title, version.year).version.label)
            }
        }
        if (message.isNotBlank()) Text(message)
    }
}

internal class SubtitleSearchState(
    private val model: LibraryModel,
    private val item: Item,
    private val scope: CoroutineScope,
    private val status: (String) -> Unit,
    private val attach: (MediaItem.SubtitleConfiguration) -> Unit,
) {
    var open by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var results by mutableStateOf(emptyList<SubtitleResult>())
        private set
    var message by mutableStateOf("")
        private set
    var remaining by mutableStateOf<Int?>(null)
    val searchFocus = FocusRequester()
    var searchAttached = false
    var restore by mutableStateOf(false)

    fun dismiss() {
        open = false
        restore = true
    }

    fun search() {
        if (loading) {
            open = true
            return
        }
        scope.launch {
            loading = true
            results = emptyList()
            message = ""
            open = true
            try {
                if (!model.repo.subtitles.configured()) {
                    message = "Ayarlar’dan OpenSubtitles veya Subdl API anahtarı ekleyin."
                    return@launch
                }
                val response = withContext(Dispatchers.IO) {
                    model.repo.subtitles.search(model.repo.subtitleQuery(item))
                }
                results = response.entries
                message = response.message.ifBlank { if (results.isEmpty()) "Türkçe altyazı bulunamadı." else "" }
            } catch (e: CancellationException) { throw e }
            catch (e: SubtitleFailure) { message = e.message.orEmpty() }
            catch (_: Exception) { message = "Altyazı araması tamamlanamadı. Yeniden deneyin." }
            finally { loading = false }
        }
    }

    fun select(result: SubtitleResult) {
        if (loading) return
        scope.launch {
            loading = true
            try {
                val key = model.repo.preferenceKey(item)
                val downloaded = withContext(Dispatchers.IO) {
                    model.repo.subtitles.download(key, result)
                }
                remaining = downloaded.second
                downloaded.second?.let { model.prefs.set("subtitle_remaining", it.toString()) }
                model.prefs.set("text_$key", "downloaded")
                status("Altyazı eklendi" + (downloaded.second?.let { " · OpenSubtitles kalan indirme: $it" } ?: ""))
                attach(subtitleConfiguration(downloaded.first))
                message = "Altyazı eklendi."
                dismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: SubtitleFailure) {
                message = e.message.orEmpty()
                if (e.quota && result.source == "OpenSubtitles") {
                    try {
                        val fallback = withContext(Dispatchers.IO) {
                            model.repo.subtitles.search(model.repo.subtitleQuery(item), skipOpen = true)
                        }
                        results = fallback.entries
                        message = fallback.message
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { message = "OpenSubtitles günlük sınırı doldu · Subdl araması tamamlanamadı." }
                }
            }
            catch (_: Exception) { message = "Altyazı indirilemedi. Yeniden deneyin." }
            finally { loading = false }
        }
    }
}

@Composable
internal fun rememberSubtitleSearchState(
    model: LibraryModel,
    item: Item,
    status: (String) -> Unit = {},
    attach: (MediaItem.SubtitleConfiguration) -> Unit,
): SubtitleSearchState = key(model, item.id) {
    val scope = rememberCoroutineScope()
    val currentStatus by rememberUpdatedState(status)
    val currentAttach by rememberUpdatedState(attach)
    val state = remember(model, item.id) {
        SubtitleSearchState(model, item, scope, { currentStatus(it) }, { currentAttach(it) })
    }
    LaunchedEffect(state) {
        state.remaining = model.prefs.text("subtitle_remaining").first().toIntOrNull()
    }
    state
}

@Composable
internal fun SubtitleSearch(state: SubtitleSearchState) {
    DisposableEffect(state) {
        state.searchAttached = true
        onDispose { state.searchAttached = false }
    }
    Column {
        Button(onClick = state::search, enabled = !state.loading || !state.open,
            modifier = Modifier.focusRequester(state.searchFocus).testTag("subtitle-search")) { Text("Altyazı ara") }
        if (!state.open && state.message.isNotBlank()) Text(state.message, color = Ink.accent)
        state.remaining?.let { Text("OpenSubtitles · kalan indirme: $it") }
    }
}

/** Call last in the player's root Box so results cover every other player layer. */
@Composable
internal fun SubtitleResults(state: SubtitleSearchState, panel: Boolean, playerFocus: FocusRequester) {
    LaunchedEffect(state.open, state.restore, panel) {
        if (!state.open && state.restore) {
            withFrameNanos { }
            if (panel && state.searchAttached) state.searchFocus.requestFocus()
            else playerFocus.requestFocus()
            state.restore = false
        }
    }
    BackHandler(state.open) { state.dismiss() }
    if (!state.open) return
    val first = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(state.results, state.loading) {
        if (!state.loading && state.results.isNotEmpty()) {
            list.scrollToItem(1)
            withFrameNanos { }
            first.requestFocus()
        } else close.requestFocus()
    }
    Box(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .64f))
            .pointerInput(Unit) { detectTapGestures { } }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        GlassPanel(Modifier.padding(32.dp).widthIn(max = 760.dp).fillMaxWidth()
            .heightIn(max = 440.dp).testTag("subtitle-results")) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Altyazı ara · Türkçe")
                Button(onClick = state::dismiss, modifier = Modifier.focusRequester(close)) { Text("Kapat") }
                LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        if (state.loading) Text("Altyazılar yükleniyor…")
                        if (state.message.isNotBlank()) Text(state.message)
                        state.remaining?.let { Text("OpenSubtitles · kalan indirme: $it") }
                    }
                    items(state.results.size) { index ->
                        val result = state.results[index]
                        Button(enabled = !state.loading,
                            modifier = Modifier.then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .testTag("subtitle-result-$index"),
                            onClick = { state.select(result) }) {
                            Column {
                                Text("${languageName(result.language)} · ${result.release}")
                                Text("${result.downloads} indirme · ${result.source}")
                            }
                        }
                    }
                    if (!state.loading) item { Button(onClick = state::search) { Text("Yeniden ara") } }
                }
            }
        }
    }
}

internal fun subtitleConfiguration(file: File): MediaItem.SubtitleConfiguration =
    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
        .setMimeType(SubtitleType.entries.first { it.extension == file.extension }.mime)
        .setLanguage("tr").setLabel("Türkçe · İndirilen altyazı")
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()

@Composable
internal fun SubtitleKeys(model: LibraryModel) {
    val fields = listOf("opensubtitles_key" to "OpenSubtitles API anahtarı", "opensubtitles_user" to "OpenSubtitles kullanıcı adı",
        "opensubtitles_password" to "OpenSubtitles parola", "subdl_key" to "Subdl API anahtarı")
    val values = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { fields.forEach { values[it.first] = model.prefs.secret(it.first) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("İnternetten altyazı")
        if (model.repo.subtitles.hasBuiltInOpenKey) Text("Yerleşik OpenSubtitles anahtarı var; alan boşsa o kullanılır.")
        fields.forEach { (key, label) -> Field(label, values[key].orEmpty(), { values[key] = it }, true) }
        Button(onClick = { scope.launch {
            try { fields.forEach { model.prefs.setSecret(it.first, values[it.first].orEmpty()) }; message = "Altyazı ayarları kaydedildi." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Altyazı ayarları kaydedilemedi." }
        } }) { Text("Altyazı ayarlarını kaydet") }
        if (message.isNotEmpty()) Text(message)
    }
}

@Composable
internal fun BoxScope.SubtitleNotice(message: String, clear: () -> Unit) {
    if (message.isBlank()) return
    LaunchedEffect(message) {
        val until = System.currentTimeMillis() + 4000
        wallClockTicks(250).first { it >= until }
        clear()
    }
    GlassPanel(Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Text(message, Modifier.padding(16.dp))
    }
}
