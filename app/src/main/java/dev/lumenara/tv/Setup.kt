package dev.lumenara.tv

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import dev.lumenara.data.*
import dev.lumenara.design.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun Field(
    label: String,
    value: String,
    change: (String) -> Unit,
    secret: Boolean = false,
    tag: String = "setup-field",
) {
    var focus by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(LumenColors.surface3, RoundedCornerShape(Radii.button))
        .border(2.dp, if (focus) LumenColors.focus else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(Radii.button))
        .padding(horizontal = Spacing.cardGap, vertical = Spacing.unit)) {
        Text(label, color = LumenColors.textSecondary, style = LumenType.caption)
        BasicTextField(
            value = value,
            onValueChange = change,
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp)
                .onFocusChanged { focus = it.isFocused }
                .testTag(tag).semantics { contentDescription = label },
            textStyle = LumenType.body.copy(color = LumenColors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(LumenColors.focus),
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable
internal fun Setup(model: LibraryModel, sources: List<Source>, openRestore: () -> Unit, openHome: () -> Unit) {
    val context = LocalContext.current
    var method by rememberSaveable { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var http by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    val busy by model.busy.collectAsState()
    val notice by model.notice.collectAsState()
    val stage by model.importProgress.stage.collectAsState()
    val resolvedAddress by model.importProgress.address.collectAsState()
    LaunchedEffect(resolvedAddress) {
        if (resolvedAddress.isNotEmpty()) address = resolvedAddress
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(method) { withFrameNanos { }; first.requestFocus() }
    BackHandler(method != null) { method = null }
    fun choose(type: String) {
        editing = null
        method = type
        address = ""
        pass = ""
        model.notice.value = ""
    }
    val file =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    .onSuccess { address = uri.toString() }
                    .onFailure {
                        model.notice.value =
                            "Kalıcı dosya erişimi alınamadı. Başka bir belge sağlayıcısı seçin."
                    }
            }
        }
    Row(Modifier.fillMaxSize().testTag("setup-screen"), horizontalArrangement = Arrangement.spacedBy(Spacing.titleGap)) {
        LazyColumn(
            modifier = Modifier.weight(if (method == null) .56f else .66f).testTag("source-setup"),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
            contentPadding = PaddingValues(bottom = Spacing.titleGap),
        ) {
            item {
                Text(if (method == null) "ADIM 1 / 2" else "ADIM 2 / 2", style = LumenType.caption, color = LumenColors.textSecondary)
            }
            if (method == null) {
                item { Text("Hoş geldiniz", style = LumenType.display, color = LumenColors.textPrimary) }
                item { Text("IPTV listenizi ekleyin; kanallar, filmler ve diziler tek yerde.", style = LumenType.body, color = LumenColors.textSecondary) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                        LumenButton(onClick = { choose("M3U") }, modifier = Modifier.weight(1f).focusRequester(first).testTag("setup-add-m3u")) { Text("Liste adresi gir") }
                        LumenButton(onClick = { choose("Xtream") }, style = LumenButtonStyle.Secondary, modifier = Modifier.weight(1f)) { Text("Xtream ile giriş") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                        LumenButton(onClick = { choose("Dosya") }, style = LumenButtonStyle.Secondary) { Text("Dosyadan") }
                        LumenButton(enabled = !busy, onClick = openRestore,
                            style = LumenButtonStyle.Secondary, modifier = Modifier.testTag("setup-restore")) { Text("Yedekten geri yükle") }
                    }
                }
            } else {
                item {
                    LumenButton(onClick = { method = null }, style = LumenButtonStyle.Secondary,
                        modifier = Modifier.focusRequester(first)) {
                        LumenIcon(LumenIcon.Back, null)
                        Text("Yöntemi değiştir")
                    }
                }
                item { Text(when (method) { "Xtream" -> "Xtream ile giriş"; "Dosya" -> "Dosyadan ekle"; else -> "Liste adresi gir" }, style = LumenType.title) }
                item {
                    Text(
                        when (method) {
                            "Dosya" ->
                                "TV depolamasından veya bağlı USB aygıtından bir M3U listesi seçin."
                            "Xtream" -> "Sağlayıcınızın sunucu adresini ve hesap bilgilerini girin."
                            else -> "Sağlayıcınızın verdiği M3U liste adresini girin."
                        },
                        color = LumenColors.textSecondary,
                    )
                }
                item { Field("Liste adı", name, { name = it }, tag = "setup-name") }
                item {
                    if (method == "Dosya")
                        LumenButton(
                            style = LumenButtonStyle.Secondary,
                            onClick = {
                                runCatching { file.launch(arrayOf("*/*")) }
                                    .onFailure {
                                        model.notice.value = "Bu TV’de belge seçici bulunamadı."
                                    }
                            }
                        ) {
                            Text(if (address.isEmpty()) "M3U dosyası seç" else "Dosyayı değiştir")
                        }
                    else
                        Field(
                            if (method == "Xtream") "Sunucu adresi" else "Liste adresi",
                            address,
                            { address = it },
                            tag = "setup-address",
                        )
                }
                if (method == "Xtream") {
                    item { Field("Kullanıcı adı", user, { user = it }) }
                    item { Field("Şifre", pass, { pass = it }, true) }
                }
                item {
                    ListRow(onClick = { advanced = !advanced }, modifier = Modifier.testTag("setup-advanced")) {
                        Text("Gelişmiş", style = LumenType.cardTitle, modifier = Modifier.weight(1f))
                        LumenIcon(if (advanced) LumenIcon.ChevronUp else LumenIcon.ChevronDown, null)
                    }
                }
                if (advanced) item {
                    ListRow(onClick = { http = !http }, modifier = Modifier.testTag("setup-http-compat")) {
                        Text("HTTP uyumluluğu · " + if (http) "Açık" else "Kapalı", style = LumenType.cardTitle)
                    }
                }
                item {
                    LumenButton(
                        modifier = Modifier.testTag("setup-submit"),
                        enabled =
                            !busy &&
                                name.isNotBlank() &&
                                address.isNotBlank() &&
                                (method != "Xtream" || user.isNotBlank() && pass.isNotEmpty()),
                        onClick = {
                            val spec =
                                SourceSpec(
                                    address.trim(),
                                    http,
                                    if (method == "Xtream") user else "",
                                    if (method == "Xtream") pass else "",
                                )
                            model.job(if (editing == null) "Kaynak eklendi." else "Kaynak güncellendi.") {
                                val id = editing
                                if (id == null) model.repo.add(name, spec, model.importProgress)
                                else model.repo.editSource(id, name, spec, model.importProgress)
                                editing = null
                                pass = ""
                                address = ""
                                name = ""
                                openHome()
                            }
                        },
                    ) {
                        Text(if (busy) stage.ifEmpty { "İşlem sürüyor…" } else if (editing == null) "Bağlantıyı sına ve ekle" else "Bağlantıyı sına ve kaydet")
                    }
                }
            }
            if (busy) item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    LumenIcon(LumenIcon.Clock, null, tint = LumenColors.textSecondary)
                    Text(stage.ifEmpty { "İşlem sürüyor…" }, style = LumenType.meta)
                }
                LumenButton(style = LumenButtonStyle.Secondary, onClick = model::cancelJob,
                    modifier = Modifier.testTag("setup-cancel")) { Text("İptal et") }
            }
            if (notice.isNotEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    val success = notice in listOf("Kaynak eklendi.", "Kaynak güncellendi.")
                    LumenIcon(if (success) LumenIcon.Check else LumenIcon.Info, null,
                        tint = if (success) LumenColors.success else LumenColors.error)
                    Text(notice, style = LumenType.meta)
                }
            }
            items(sources, key = { it.id }) { source ->
                var guideInput by remember { mutableStateOf(false) }
                val limit by
                    remember(source.id) { model.prefs.connections(source.id) }.collectAsState(1)
                val configuredLimit by
                    remember(source.id) { model.prefs.text("limit_${source.id}", "1") }
                        .collectAsState("1")
                val sync by
                    remember(source.id) { model.prefs.text("sync_${source.id}") }.collectAsState("")
                val scope = rememberCoroutineScope()
                var guideAddress by remember { mutableStateOf("") }
                Column {
                    Text(source.name, style = LumenType.cardTitle)
                    if (sync.isNotEmpty()) Text(sync, color = LumenColors.accent)
                    Text(
                        "${source.count} içerik · ${DateFormat.getDateTimeInstance().format(Date(source.refreshed))}",
                        color = LumenColors.textSecondary,
                    )
                    LumenButton(
                            style = LumenButtonStyle.Secondary,
                        onClick = {
                            scope.launch {
                                model.prefs.set(
                                    "limit_${source.id}",
                                    ((configuredLimit.toIntOrNull() ?: 1) % 4 + 1).toString(),
                                )
                            }
                        }
                    ) {
                        Text("Bağlantı sınırı · $configuredLimit (kullanılabilir $limit)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LumenButton(style = LumenButtonStyle.Secondary, enabled = !busy,
                            modifier = Modifier.testTag("source-edit-${source.id}"), onClick = {
                                model.job("") {
                                    val spec = model.repo.sourceSpec(source.id)
                                    editing = source.id
                                    method = if (spec.user.isNotEmpty()) "Xtream" else if (spec.address.startsWith("content://")) "Dosya" else "M3U"
                                    name = source.name
                                    address = spec.address
                                    user = spec.user
                                    pass = spec.password
                                    http = spec.allowHttp
                                }
                            }) { Text("Düzenle") }
                        LumenButton(
                            style = LumenButtonStyle.Secondary,
                            enabled = !busy,
                            onClick = {
                                model.job("Liste yenilendi.") {
                                    model.repo.refresh(source.id, model.importProgress)
                                    model.repo.refreshGuides(source.id, model.importProgress)
                                }
                            },
                        ) {
                            Text("Yenile")
                        }
                        LumenButton(enabled = !busy, onClick = { guideInput = !guideInput }) {
                            Text("EPG ekle")
                        }
                        LumenButton(
                            style = LumenButtonStyle.Secondary,
                            enabled = !busy,
                            onClick = {
                                if (pendingDelete == source.id)
                                    model.job("Kaynak silindi.") {
                                        model.repo.remove(source.id)
                                        pendingDelete = ""
                                    }
                                else pendingDelete = source.id
                            },
                        ) {
                            Text(if (pendingDelete == source.id) "Silmeyi onayla" else "Sil")
                        }
                        if (pendingDelete == source.id)
                            LumenButton(onClick = { pendingDelete = "" }) { Text("Vazgeç") }
                    }
                    if (guideInput) {
                        Field(
                            "XMLTV adresi (Xtream rehberi için boş bırakın)",
                            guideAddress,
                            { guideAddress = it },
                        )
                        LumenButton(
                            style = LumenButtonStyle.Secondary,
                            enabled = !busy,
                            onClick = {
                                model.job("Rehber güncellendi.") {
                                    model.repo.addGuide(source.id, guideAddress.trim())
                                    guideInput = false
                                    guideAddress = ""
                                }
                            },
                        ) {
                            Text("Rehberi al")
                        }
                    }
                }
            }
        }
        Panel(Modifier.weight(if (method == null) .44f else .34f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.titleGap),
                verticalArrangement = Arrangement.spacedBy(Spacing.cardGap), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Telefonla ekle", style = LumenType.title)
                Text("Telefonunuzla okutun, adresi oradan yapıştırın.", style = LumenType.meta, textAlign = TextAlign.Center)
                PairPanel(model, openHome)
            }
        }
    }
}
