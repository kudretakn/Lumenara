package dev.streamora.tv

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import dev.streamora.data.*
import dev.streamora.design.Ink
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun Field(
    label: String,
    value: String,
    change: (String) -> Unit,
    secret: Boolean = false,
) {
    var focus by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Ink.muted, fontSize = 13.sp)
        BasicTextField(
            value,
            change,
            Modifier.fillMaxWidth()
                .onFocusChanged { focus = it.isFocused }
                .background(if (focus) Ink.focus else Ink.surface, RoundedCornerShape(6.dp))
                .padding(9.dp),
            textStyle =
                TextStyle(color = if (focus) Ink.background else Ink.text, fontSize = 16.sp),
            singleLine = true,
            visualTransformation =
                if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable
internal fun Setup(model: LibraryModel, sources: List<Source>, openHome: () -> Unit) {
    val context = LocalContext.current
    var method by remember { mutableStateOf("M3U") }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var http by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf("") }
    val busy by model.busy.collectAsState()
    val notice by model.notice.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
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
    LazyColumn(
        modifier = Modifier.widthIn(max = 470.dp).testTag("source-setup"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 4.dp, top = 4.dp, bottom = 24.dp, end = 4.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("M3U", "Dosya", "Xtream", "Telefon").forEachIndexed { i, type ->
                    Button(
                        onClick = {
                            method = type
                            address = ""
                            pass = ""
                            model.notice.value = ""
                        },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) {
                        Text(if (method == type) "✓ $type" else type)
                    }
                }
            }
        }
        if (method == "Telefon") item { PairPanel(model, openHome) }
        else {
            item {
                Text(
                    when (method) {
                        "Dosya" ->
                            "TV depolamasından veya bağlı USB aygıtından bir M3U listesi seçin."
                        "Xtream" -> "Sağlayıcınızın sunucu adresini ve hesap bilgilerini girin."
                        else -> "Sağlayıcınızın verdiği M3U liste adresini girin."
                    },
                    color = Ink.muted,
                )
            }
            item { Field("Liste adı", name, { name = it }) }
            item {
                if (method == "Dosya")
                    Button(
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
                    )
            }
            if (method == "Xtream") {
                item { Field("Kullanıcı adı", user, { user = it }) }
                item { Field("Şifre", pass, { pass = it }, true) }
            }
            item {
                Button(onClick = { http = !http }) {
                    Text("HTTP uyumluluğu · " + if (http) "Açık" else "Kapalı")
                }
            }
            item {
                Button(
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
                        model.job("Kaynak eklendi.") {
                            model.repo.add(name, spec)
                            pass = ""
                            address = ""
                            name = ""
                            openHome()
                        }
                    },
                ) {
                    Text(if (busy) "Kaynak işleniyor…" else "Bağlantıyı sına ve ekle")
                }
            }
        }
        if (notice.isNotEmpty()) item { Text(notice, color = Ink.accent) }
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
                Text(source.name, fontSize = 18.sp)
                if (sync.isNotEmpty()) Text(sync, color = Ink.accent)
                Text(
                    "${source.count} içerik · ${DateFormat.getDateTimeInstance().format(Date(source.refreshed))}",
                    color = Ink.muted,
                )
                Button(
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
                    Button(
                        enabled = !busy,
                        onClick = {
                            model.job("Liste yenilendi.") {
                                model.repo.refresh(source.id)
                                model.repo.refreshGuides(source.id)
                            }
                        },
                    ) {
                        Text("Yenile")
                    }
                    Button(enabled = !busy, onClick = { guideInput = !guideInput }) {
                        Text("EPG ekle")
                    }
                    Button(
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
                        Button(onClick = { pendingDelete = "" }) { Text("Vazgeç") }
                }
                if (guideInput) {
                    Field(
                        "XMLTV adresi (Xtream rehberi için boş bırakın)",
                        guideAddress,
                        { guideAddress = it },
                    )
                    Button(
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
}
