package dev.lumenara.tv

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.media.MediaCodecList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.imageLoader
import dev.lumenara.data.*
import dev.lumenara.design.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.*

@Composable
private fun Choice(model: LibraryModel, key: String, label: String, options: List<String>, default: String = options.first()) {
    val current by
        remember(key) { model.prefs.text(key, default) }
            .collectAsStateWithLifecycle(default)
    val scope = rememberCoroutineScope()
    ListRow(
        modifier = Modifier.semantics { text = AnnotatedString("$label · $current") },
        onClick = {
            scope.launch {
                model.prefs.set(key, options[(options.indexOf(current) + 1) % options.size])
            }
        }
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = LumenType.cardTitle)
            val description = when (key) {
                "frame_rate" -> "Ekran hızını içeriğe uyarla"
                "watch_next" -> "Kaldığın yerden devam et"
                "engine" -> "Yeni yayınlar için oynatıcı"
                "fallback" -> "Desteklenmeyen yayınlarda geçiş yap"
                "buffer" -> "Bağlantına uygun önbellek"
                "audio" -> "Tercih edilen ses parçası"
                "sub" -> "Tercih edilen altyazı"
                "size" -> "Altyazı metninin boyutu"
                "color" -> "Altyazı metninin rengi"
                else -> ""
            }
            if (description.isNotEmpty()) Text(description, style = LumenType.meta)
        }
        Text(current, style = LumenType.meta, modifier = Modifier.widthIn(max = 160.dp))
        if (options.toSet() == setOf("Açık", "Kapalı")) SettingsToggle(current == "Açık")
        LumenIcon(LumenIcon.ChevronRight, null, tint = LumenColors.textSecondary)
    }
}

@Composable
@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal fun SettingsScreen(
    model: LibraryModel,
    sources: List<Source>,
    sourceFocus: FocusRequester,
    openSources: () -> Unit,
) {
    val showAdult by model.prefs.showAdult.collectAsStateWithLifecycle(false)
    val settingsScope = rememberCoroutineScope()
    val context = LocalContext.current
    val busy by model.busy.collectAsStateWithLifecycle()
    val stage by model.importProgress.stage.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(model.repo.tmdbConfigured()) }
    var expert by rememberSaveable { mutableStateOf(false) }
    val release by model.update.collectAsStateWithLifecycle()
    val installation by UpdateInstaller.state.collectAsStateWithLifecycle()
    var awaitingInstallPermission by rememberSaveable { mutableStateOf(false) }
    val install: (Update) -> Unit = { info ->
        model.job("") { UpdateInstaller.install(context.applicationContext, info) }
    }
    val installPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            val info = release
            if (!context.packageManager.canRequestPackageInstalls())
                UpdateInstaller.failed("Bu kaynaktan uygulama yükleme izni verilmedi.")
            else if (info != null) install(info)
            else UpdateInstaller.failed("Sürüm bilgisi bulunamadı; yeniden kontrol edin.")
        }
    }
    val confirmInstallation = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* The session receiver reports success, cancellation and failure. */ }
    LaunchedEffect(installation.confirmation) {
        installation.confirmation?.let { confirmation ->
            UpdateInstaller.state.value = installation.copy(confirmation = null)
            runCatching { confirmInstallation.launch(confirmation) }
                .onFailure { UpdateInstaller.failed("Kurulum onayı açılamadı: ${it.localizedMessage}") }
        }
    }
    val configuredRepo by remember { model.prefs.text("github") }.collectAsStateWithLifecycle("")
    var github by remember(configuredRepo) { mutableStateOf(configuredRepo) }
    var codecs by remember { mutableStateOf("") }
    var disk by remember { mutableLongStateOf(0) }
    val log by remember { model.prefs.text("events") }.collectAsStateWithLifecycle("")
    var backupMode by remember { mutableStateOf<String?>(null) }
    val hours by remember { model.prefs.text("hours", "12") }.collectAsStateWithLifecycle("12")
    LaunchedEffect(Unit) {
        disk = withContext(Dispatchers.IO) { context.imageLoader.diskCache?.size ?: 0L }
    }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            uri ->
            if (uri != null)
                model.job("Tanılama raporu kaydedildi.") {
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri))
                            .bufferedWriter()
                            .use {
                                it.write(
                                    "Lumenara · API ${android.os.Build.VERSION.SDK_INT}\n$codecs\nKaynak sayısı: ${sources.size}\n$log"
                                )
                            }
                    }
                }
        }
    val download =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
        ) { uri ->
            val info = release
            if (uri != null && info != null)
                model.job("İmzası ve özeti doğrulanan APK kaydedildi.") {
                    val file = Updates.verifiedFile(context, info)
                    try {
                        withContext(Dispatchers.IO) {
                            checkNotNull(context.contentResolver.openOutputStream(uri)).use { out ->
                                file.inputStream().use { it.copyTo(out) }
                            }
                        }
                    } finally {
                        file.delete()
                    }
                }
        }
    val groups = listOf("Kaynaklar", "Oynatıcı", "Altyazı", "İçerik (+18)", "Depolama", "Güncellemeler", "Hakkında")
    var selectedGroup by rememberSaveable { mutableStateOf("Kaynaklar") }
    val list = rememberLazyListState()
    var lastEntry by rememberSaveable { mutableIntStateOf(0) }
    val returnEntry = remember(backupMode, selectedGroup) { lastEntry }
    var positioned by remember { mutableStateOf(false) }
    backupMode?.let { mode ->
        BackupScreen(model, restore = mode == "restore", finish = {
            backupMode = null
            configured = model.repo.tmdbConfigured()
        })
        return
    }
    LaunchedEffect(selectedGroup) {
        positioned = false
        list.scrollToItem(returnEntry)
        positioned = true
    }
    Column(Modifier.fillMaxSize()) {
        Text("Ayarlar", style = LumenType.headline, color = LumenColors.textPrimary)
        Spacer(Modifier.height(Spacing.titleGap))
        if (busy) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap), verticalAlignment = Alignment.CenterVertically) {
            Text(stage.ifEmpty { "İşlem sürüyor…" }, style = LumenType.meta)
            LumenButton(style = LumenButtonStyle.Secondary, onClick = model::cancelJob,
                modifier = Modifier.testTag("settings-cancel")) { Text("İptal et") }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.titleGap)) {
            Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(Spacing.iconText)) {
                groups.forEach { group ->
                    ListRow(onClick = {
                        if (selectedGroup != group) {
                            lastEntry = when (group) {
                                "Oynatıcı", "Depolama" -> 1
                                "Hakkında" -> 3
                                else -> 0
                            }
                            selectedGroup = group
                        }
                    }, selected = selectedGroup == group, modifier = Modifier.height(56.dp).testTag("settings-group-$group")) {
                        Text(group, style = LumenType.cardTitle, modifier = Modifier.weight(1f))
                        if (group == "Güncellemeler" && release != null)
                            Box(Modifier.size(6.dp).background(LumenColors.accent, RoundedCornerShape(3.dp)))
                    }
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().padding(Spacing.cardGap).testTag("settings-list"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.unit),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    var entryIndex = 0
                    var group = "Kaynaklar"
                    fun LazyListScope.setting(content: @Composable () -> Unit) {
                        if (group != selectedGroup) return
                        val index = entryIndex++
                        item(key = "$selectedGroup-$index") {
                            if (index == returnEntry && positioned) LaunchedEffect(Unit) {
                                withFrameNanos { }
                                sourceFocus.requestFocus()
                            }
                            Column(Modifier
                                .then(if (index == returnEntry) Modifier.focusRequester(sourceFocus) else Modifier)
                                .onFocusChanged { if (it.hasFocus) lastEntry = index }) { content() }
                        }
                    }
                    setting {
                        LumenButton(style = LumenButtonStyle.Secondary, onClick = openSources) {
                            Text("IPTV Kaynakları")
                        }
                    }
                    setting { Text("${sources.size} kaynak · ${sources.sumOf { it.count }} içerik", style = LumenType.meta) }
                    group = "Oynatıcı"
                    setting { Text("Oynatıcı", style = LumenType.title) }
                    setting { Choice(model, "frame_rate", "Kare hızını eşle", dev.lumenara.catalog.frameRateOptions, dev.lumenara.catalog.FRAME_RATE_DEFAULT) }
                    setting { Choice(model, "watch_next", "Android TV ana ekranında göster", listOf("Açık", "Kapalı")) }
                    setting { Choice(model, "engine", "Varsayılan motor", listOf("Media3", "VLC")) }
                    setting { Choice(model, "fallback", "Codec hatasında VLC", listOf("Açık", "Kapalı")) }
                    setting { Choice(model, "buffer", "Media3 tamponu", listOf("Dengeli", "Hızlı", "Kararlı")) }
                    setting { Choice(model, "audio", "Ses dili", listOf("Otomatik (Türkçe)", "tr", "en", "de", "fr")) }
                    group = "Altyazı"
                    setting { Choice(model, "sub", "Altyazı dili", listOf("Otomatik (Türkçe)", "Kapalı", "tr", "en", "de", "fr")) }
                    setting { Choice(model, "size", "Altyazı boyutu", listOf("Orta", "Büyük", "Küçük")) }
                    setting { Choice(model, "color", "Altyazı rengi", listOf("Beyaz", "Sarı")) }
                    group = "İçerik (+18)"
                    setting {
                        ListRow(onClick = { settingsScope.launch { model.prefs.setShowAdult(!showAdult) } },
                            modifier = Modifier.testTag("show-adult")) {
                            Text("+18 içerikleri göster · " + if (showAdult) "Açık" else "Kapalı",
                                style = LumenType.cardTitle, modifier = Modifier.weight(1f))
                            // D0 has no toggle; this private visual keeps the existing click action.
                            SettingsToggle(showAdult)
                        }
                    }
                    group = "Depolama"
                    setting {
                        Text("Depolama ve yenileme", style = LumenType.title)
                        Text("${sources.size} kaynak · ${sources.sumOf { it.count }} içerik", color = LumenColors.textSecondary)
                    }
                    setting {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LumenButton(style = LumenButtonStyle.Secondary, enabled = !busy, onClick = { backupMode = "backup" }, modifier = Modifier.testTag("settings-backup")) { Text("Yedekle") }
                            LumenButton(style = LumenButtonStyle.Secondary, enabled = !busy, onClick = { backupMode = "restore" }, modifier = Modifier.testTag("settings-restore")) { Text("Geri yükle") }
                        }
                    }
                    setting {
                        LumenButton(style = LumenButtonStyle.Secondary,
                            enabled = !busy,
                            onClick = {
                                model.job("Yenileme aralığı değiştirildi.") {
                                    val choices = listOf("6", "12", "24", "48")
                                    val next = choices[(choices.indexOf(hours) + 1) % choices.size]
                                    model.prefs.set("hours", next)
                                    model.repo.schedule(next.toLong())
                                }
                            },
                        ) {
                            Text("Otomatik yenileme · $hours saat")
                        }
                    }
                    setting {
                        LumenButton(style = LumenButtonStyle.Secondary,
                            enabled = !busy && sources.isNotEmpty(),
                            onClick = {
                                model.job("Kaynaklar ve rehberler yenilendi.") {
                                    sources.forEach {
                                        model.repo.refresh(it.id, model.importProgress)
                                        model.repo.refreshGuides(it.id, model.importProgress)
                                    }
                                }
                            },
                        ) {
                            Text("Şimdi yenile")
                        }
                    }
                    setting {
                        LumenButton(style = LumenButtonStyle.Secondary,
                            enabled = !busy,
                            onClick = {
                                model.job("Görsel önbelleği temizlendi.") {
                                    context.imageLoader.memoryCache?.clear()
                                    withContext(Dispatchers.IO) { context.imageLoader.diskCache?.clear() }
                                    disk = 0
                                }
                            },
                        ) {
                            Text("Görsel önbelleğini temizle · ${disk/1048576} MB")
                        }
                    }
                    group = "Altyazı"
                    setting { SubtitleKeys(model) }
                    group = "İçerik (+18)"
                    setting {
                        Text("Film ve dizi bilgileri", style = LumenType.title)
                        if (model.repo.tmdbBuiltIn())
                            Text("Yerleşik TMDB erişimi var; alan boşsa o kullanılır.", color = LumenColors.textSecondary)
                        Field("TMDB API Read Access Token", token, { token = it }, true)
                    }
                    setting {
                        LumenButton(style = LumenButtonStyle.Secondary,
                            enabled = !busy && (token.isNotBlank() || configured),
                            onClick = {
                                model.job("TMDB ayarı kaydedildi.") {
                                    model.repo.configureTmdb(token)
                                    configured = model.repo.tmdbConfigured()
                                    token = ""
                                }
                            },
                        ) {
                            Text(
                                if (configured && token.isEmpty()) "TMDB erişimini kaldır"
                                else "TMDB erişimini kaydet"
                            )
                        }
                    }
                    group = "Hakkında"
                    setting { Text("Lumenara ${BuildConfig.VERSION_NAME}", style = LumenType.title) }
                    setting { Text("Türkçe", style = LumenType.meta) }
                    setting {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val tmdbRequest = remember(context, density) {
                            coil.request.ImageRequest.Builder(context).data(R.raw.tmdb)
                                .size(with(density) { 75.dp.roundToPx() }, with(density) { 14.dp.roundToPx() })
                                .allowRgb565(true).crossfade(false).build()
                        }
                        AsyncImage(tmdbRequest, "TMDB", Modifier.width(75.dp).height(14.dp))
                        Text(
                            "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                            style = LumenType.caption,
                            color = LumenColors.textSecondary,
                        )
                    }
                    group = "Güncellemeler"
                    setting {
                        Text("Uygulama güncellemeleri", style = LumenType.title)
                        Field("GitHub deposu · sahip/depo", github, { github = it })
                        if (github.isBlank())
                            Text("Önerilen depo: kudretakn/Lumenara-releases", color = LumenColors.textSecondary)
                    }
                    setting {
                        LumenButton(style = LumenButtonStyle.Secondary,
                            enabled = !busy && (github.isBlank() || Updates.validRepo(github)),
                            onClick = {
                                model.job("Sürüm kontrolü tamamlandı.") {
                                    model.prefs.set("github", github.trim())
                                    model.checkUpdate(github.trim())
                                }
                            },
                        ) {
                            Text(
                                if (github.isBlank()) "Sürüm kontrolünü kapat"
                                else "Kaydet ve sürüm kontrolü yap"
                            )
                        }
                    }
                    release?.let { info ->
                        setting {
                            Text("Yeni sürüm · ${info.version}")
                            Text(info.notes)
                            Text(
                                "Kurulum için Android ayarlarında Lumenara’ya bu kaynaktan uygulama yükleme izni verin. İzin sonrası güncelleme devam eder.",
                                color = LumenColors.textSecondary,
                            )
                            LumenButton(style = LumenButtonStyle.Secondary,
                                enabled = !busy && !installation.active && !awaitingInstallPermission,
                                onClick = {
                                    if (context.packageManager.canRequestPackageInstalls()) install(info)
                                    else {
                                        awaitingInstallPermission = true
                                        runCatching {
                                            installPermission.launch(
                                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:${context.packageName}"))
                                            )
                                        }.onFailure {
                                            awaitingInstallPermission = false
                                            UpdateInstaller.failed("Uygulama yükleme izni ayarları açılamadı.")
                                        }
                                    }
                                },
                            ) {
                                Text("Güncelle (${if (info.version.startsWith("v")) info.version else "v${info.version}"})")
                            }
                            LumenButton(style = LumenButtonStyle.Secondary,
                                enabled = !busy,
                                onClick = {
                                    runCatching { download.launch("Lumenara-${info.version}.apk") }
                                        .onFailure {
                                            model.notice.value = "Bu TV’de dosya kaydetme hizmeti bulunamadı."
                                        }
                                },
                            ) {
                                Text("APK’yı dosyaya kaydet")
                            }
                        }
                    }
                    if (installation.message.isNotEmpty()) setting { Text(installation.message, color = LumenColors.accent) }
                    group = "Hakkında"
                    setting {
                        LumenButton(style = LumenButtonStyle.Secondary, onClick = { expert = !expert }) {
                            Text(if (expert) "Uzman modunu kapat" else "Uzman modu")
                        }
                    }
                    if (expert) {
                        setting { Choice(model, "diagnostics", "Yerel olay kaydı", listOf("Kapalı", "Açık")) }
                        setting {
                            LumenButton(style = LumenButtonStyle.Secondary,
                                onClick = {
                                    model.job("Çözücüler listelendi.") {
                                        codecs =
                                            withContext(Dispatchers.Default) {
                                                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                                                    .codecInfos
                                                    .filter {
                                                        !it.isEncoder &&
                                                            it.supportedTypes.any { t ->
                                                                t.startsWith("video/")
                                                            }
                                                    }
                                                    .joinToString("\n") { it.name }
                                            }
                                    }
                                }
                            ) {
                                Text("Video çözücüleri")
                            }
                            Text(codecs, style = LumenType.meta)
                        }
                        setting {
                            Text(log, style = LumenType.caption)
                            LumenButton(style = LumenButtonStyle.Secondary,
                                onClick = { model.job("Kayıt silindi.") { model.prefs.set("events", "") } }
                            ) {
                                Text("Hata kaydını temizle")
                            }
                        }
                        setting {
                            LumenButton(style = LumenButtonStyle.Secondary,
                                onClick = {
                                    runCatching { export.launch("lumenara-tanilama.txt") }
                                        .onFailure {
                                            model.notice.value = "Bu TV’de belge seçici bulunamadı."
                                        }
                                }
                            ) {
                                Text("Yerel tanılama raporunu kaydet")
                            }
                        }
                    }
                    group = selectedGroup
                    if (notice.isNotEmpty()) setting {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                            LumenIcon(LumenIcon.Info, null, tint = LumenColors.textSecondary)
                            Text(notice, style = LumenType.meta)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(checked: Boolean) {
    Box(Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(12.dp))
        .background(if (checked) LumenColors.accent else LumenColors.surface3)
        .padding(3.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(18.dp).background(if (checked) LumenColors.onFocus else LumenColors.textSecondary,
            RoundedCornerShape(9.dp)))
    }
}
