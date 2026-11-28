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
import androidx.compose.ui.res.stringResource
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

private data class Wp17SettingOption(val id: String, val label: Int)
private data class Wp17SettingsGroup(val id: String, val label: Int, val legacyName: String)

private val wp17SettingsGroups = listOf(
    Wp17SettingsGroup("sources", R.string.settings_group_sources, "Kaynaklar"),
    Wp17SettingsGroup("player", R.string.settings_group_player, "Oynatıcı"),
    Wp17SettingsGroup("subtitles", R.string.settings_group_subtitles, "Altyazı"),
    Wp17SettingsGroup("adult", R.string.settings_group_adult, "İçerik (+18)"),
    Wp17SettingsGroup("storage", R.string.settings_group_storage, "Depolama"),
    Wp17SettingsGroup("updates", R.string.settings_group_updates, "Güncellemeler"),
    Wp17SettingsGroup("about", R.string.settings_group_about, "Hakkında"),
)

private val wp17FrameRateOptions = listOf(
    Wp17SettingOption("off", R.string.choice_off),
    Wp17SettingOption("vod_only", R.string.choice_frame_rate_vod),
    Wp17SettingOption("always", R.string.choice_frame_rate_always),
)
private val wp17WatchNextOptions = listOf(
    Wp17SettingOption("on", R.string.choice_on),
    Wp17SettingOption("off", R.string.choice_off),
)
private val wp17EngineOptions = listOf(
    Wp17SettingOption("media3", R.string.choice_media3),
    Wp17SettingOption("vlc", R.string.choice_vlc),
)
private val wp17FallbackOptions = listOf(
    Wp17SettingOption("on", R.string.choice_on),
    Wp17SettingOption("off", R.string.choice_off),
)
private val wp17BufferOptions = listOf(
    Wp17SettingOption("balanced", R.string.choice_buffer_balanced),
    Wp17SettingOption("fast", R.string.choice_buffer_fast),
    Wp17SettingOption("steady", R.string.choice_buffer_steady),
)
private val wp17AudioOptions = listOf(
    Wp17SettingOption("auto_tr", R.string.choice_auto_tr),
    Wp17SettingOption("tr", R.string.choice_tr),
    Wp17SettingOption("en", R.string.choice_en),
    Wp17SettingOption("de", R.string.choice_de),
    Wp17SettingOption("fr", R.string.choice_fr),
)
private val wp17SubOptions = listOf(
    Wp17SettingOption("auto_tr", R.string.choice_auto_tr),
    Wp17SettingOption("off", R.string.choice_off),
    Wp17SettingOption("tr", R.string.choice_tr),
    Wp17SettingOption("en", R.string.choice_en),
    Wp17SettingOption("de", R.string.choice_de),
    Wp17SettingOption("fr", R.string.choice_fr),
)
private val wp17SizeOptions = listOf(
    Wp17SettingOption("medium", R.string.choice_size_medium),
    Wp17SettingOption("large", R.string.choice_size_large),
    Wp17SettingOption("small", R.string.choice_size_small),
)
private val wp17ColorOptions = listOf(
    Wp17SettingOption("white", R.string.choice_color_white),
    Wp17SettingOption("yellow", R.string.choice_color_yellow),
)
private val wp17DiagnosticsOptions = listOf(
    Wp17SettingOption("off", R.string.choice_off),
    Wp17SettingOption("on", R.string.choice_on),
)

@Composable
private fun Choice(model: LibraryModel, key: String, label: String,
    options: List<Wp17SettingOption>, default: String = options.first().id) {
    val current by remember(model.prefs, key, default) { model.prefs.choiceId(key, default) }
        .collectAsStateWithLifecycle(default)
    val display = options.firstOrNull { it.id == current }?.let { stringResource(it.label) } ?: current
    val scope = rememberCoroutineScope()
    ListRow(
        modifier = Modifier.semantics { text = AnnotatedString("$label · $display") },
        onClick = {
            scope.launch {
                model.prefs.set(key, options[(options.indexOfFirst { it.id == current } + 1) % options.size].id)
            }
        }
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = LumenType.cardTitle)
            val description = when (key) {
                "frame_rate" -> R.string.setting_frame_rate_description
                "watch_next" -> R.string.setting_watch_next_description
                "engine" -> R.string.setting_engine_description
                "fallback" -> R.string.setting_fallback_description
                "buffer" -> R.string.setting_buffer_description
                "audio" -> R.string.setting_audio_description
                "sub" -> R.string.setting_sub_description
                "size" -> R.string.setting_size_description
                "color" -> R.string.setting_color_description
                else -> null
            }
            if (description != null) Text(stringResource(description), style = LumenType.meta)
        }
        Text(display, style = LumenType.meta, modifier = Modifier.widthIn(max = 160.dp))
        if (options.map { it.id }.toSet() == setOf("on", "off")) SettingsToggle(current == "on")
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
    var savedGroup by rememberSaveable { mutableStateOf("sources") }
    val selectedGroup = wp17SettingsGroups.firstOrNull {
        it.id == savedGroup || it.legacyName == savedGroup
    }?.id ?: "sources"
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
        Text(stringResource(R.string.route_settings), style = LumenType.headline, color = LumenColors.textPrimary)
        Spacer(Modifier.height(Spacing.titleGap))
        if (busy) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap), verticalAlignment = Alignment.CenterVertically) {
            Text(stage.ifEmpty { "İşlem sürüyor…" }, style = LumenType.meta)
            LumenButton(style = LumenButtonStyle.Secondary, onClick = model::cancelJob,
                modifier = Modifier.testTag("settings-cancel")) { Text("İptal et") }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.titleGap)) {
            Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(Spacing.iconText)) {
                wp17SettingsGroups.forEach { (group, labelRes, legacyName) ->
                    ListRow(onClick = {
                        if (selectedGroup != group) {
                            lastEntry = when (group) {
                                "player", "storage" -> 1
                                "about" -> 3
                                else -> 0
                            }
                            savedGroup = group
                        }
                    }, selected = selectedGroup == group, modifier = Modifier.height(56.dp).testTag("settings-group-$legacyName")) {
                        Text(stringResource(labelRes), style = LumenType.cardTitle, modifier = Modifier.weight(1f))
                        if (group == "updates" && release != null)
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
                    var group = "sources"
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
                    group = "player"
                    setting { Text("Oynatıcı", style = LumenType.title) }
                    setting { Choice(model, "frame_rate", stringResource(R.string.setting_frame_rate), wp17FrameRateOptions, "vod_only") }
                    setting { Choice(model, "watch_next", stringResource(R.string.setting_watch_next), wp17WatchNextOptions) }
                    setting { Choice(model, "engine", stringResource(R.string.setting_engine), wp17EngineOptions) }
                    setting { Choice(model, "fallback", stringResource(R.string.setting_fallback), wp17FallbackOptions) }
                    setting { Choice(model, "buffer", stringResource(R.string.setting_buffer), wp17BufferOptions) }
                    setting { Choice(model, "audio", stringResource(R.string.setting_audio), wp17AudioOptions) }
                    group = "subtitles"
                    setting { Choice(model, "sub", stringResource(R.string.setting_sub), wp17SubOptions) }
                    setting { Choice(model, "size", stringResource(R.string.setting_size), wp17SizeOptions) }
                    setting { Choice(model, "color", stringResource(R.string.setting_color), wp17ColorOptions) }
                    group = "adult"
                    setting {
                        ListRow(onClick = { settingsScope.launch { model.prefs.setShowAdult(!showAdult) } },
                            modifier = Modifier.testTag("show-adult")) {
                            Text("+18 içerikleri göster · " + if (showAdult) "Açık" else "Kapalı",
                                style = LumenType.cardTitle, modifier = Modifier.weight(1f))
                            // D0 has no toggle; this private visual keeps the existing click action.
                            SettingsToggle(showAdult)
                        }
                    }
                    group = "storage"
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
                    group = "subtitles"
                    setting { SubtitleKeys(model) }
                    group = "adult"
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
                    group = "about"
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
                    group = "updates"
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
                    group = "about"
                    setting {
                        LumenButton(style = LumenButtonStyle.Secondary, onClick = { expert = !expert }) {
                            Text(if (expert) "Uzman modunu kapat" else "Uzman modu")
                        }
                    }
                    if (expert) {
                        setting { Choice(model, "diagnostics", stringResource(R.string.setting_diagnostics), wp17DiagnosticsOptions) }
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
