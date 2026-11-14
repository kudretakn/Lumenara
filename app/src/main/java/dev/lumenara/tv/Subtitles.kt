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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.focus.focusRequester
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

@Composable
internal fun SubtitleSearch(model: LibraryModel, item: Item,
                            status: (String) -> Unit = {},
                            attach: (MediaItem.SubtitleConfiguration) -> Unit) {
    var open by remember(item.id) { mutableStateOf(false) }
    var loading by remember(item.id) { mutableStateOf(false) }
    var results by remember(item.id) { mutableStateOf(emptyList<SubtitleResult>()) }
    var message by remember(item.id) { mutableStateOf("") }
    var remaining by remember(item.id) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id) { remaining = model.prefs.text("subtitle_remaining").first().toIntOrNull() }
    val searchFocus = remember { FocusRequester() }
    var restore by remember { mutableStateOf(false) }
    LaunchedEffect(open, restore) {
        if (!open && restore) { searchFocus.requestFocus(); restore = false }
    }
    fun search() {
        scope.launch {
            loading = true
            results = emptyList()
            message = ""
            try {
                if (!model.repo.subtitles.configured()) {
                    message = "Ayarlar’dan OpenSubtitles veya Subdl API anahtarı ekleyin."
                    return@launch
                }
                open = true
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
    Column {
        Button(onClick = { search() }, enabled = !loading,
            modifier = Modifier.focusRequester(searchFocus).testTag("subtitle-search")) { Text("Altyazı ara") }
        if (!open && message.isNotBlank()) Text(message, color = Ink.accent)
        remaining?.let { Text("OpenSubtitles · kalan indirme: $it") }
    }
    if (open) Dialog(onDismissRequest = { open = false; restore = true }) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }
        GlassPanel(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("subtitle-results")) {
            LazyColumn(
                Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Altyazı ara · Türkçe")
                    Button(onClick = { open = false; restore = true }, modifier = Modifier.focusRequester(first)) { Text("Kapat") }
                    if (loading) Text("Altyazılar yükleniyor…")
                    if (message.isNotBlank()) Text(message)
                    remaining?.let { Text("OpenSubtitles · kalan indirme: $it") }
                }
                items(results.size) { index ->
                    val result = results[index]
                    Button(enabled = !loading, modifier = Modifier.testTag("subtitle-result-$index"), onClick = {
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
                                open = false
                                restore = true
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
                    }) { Column {
                        Text("${languageName(result.language)} · ${result.release}")
                        Text("${result.downloads} indirme · ${result.source}")
                    } }
                }
                if (!loading) item { Button(onClick = { search() }) { Text("Yeniden ara") } }
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
