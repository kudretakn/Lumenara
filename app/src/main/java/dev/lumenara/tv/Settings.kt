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
import dev.lumenara.design.Ink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable
private fun Choice(model: LibraryModel, key: String, label: String, options: List<String>, default: String = options.first()) {
    val current by
        remember(key) { model.prefs.text(key, default) }
            .collectAsStateWithLifecycle(default)
    val scope = rememberCoroutineScope()
    Button(
        onClick = {
            scope.launch {
                model.prefs.set(key, options[(options.indexOf(current) + 1) % options.size])
            }
        }
    ) {
        Text("$label · $current")
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
    val list = rememberLazyListState()
    var lastEntry by rememberSaveable { mutableIntStateOf(0) }
    val returnEntry = remember { lastEntry }
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        list.scrollToItem(returnEntry)
        positioned = true
    }
    LazyColumn(
        state = list,
        modifier = Modifier.testTag("settings-list"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        var entryIndex = 0
        fun LazyListScope.setting(content: @Composable () -> Unit) {
            val index = entryIndex++
            item {
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
            Button(onClick = openSources) {
                Text("IPTV Kaynakları")
            }
        }
        setting { Text("Türkçe   ·   Antrasit tema   ·   Lumenara 0.1", color = Ink.muted) }
        setting { Text("Oynatıcı", fontSize = 20.sp) }
        setting { Choice(model, "frame_rate", "Kare hızını eşle", dev.lumenara.catalog.frameRateOptions, dev.lumenara.catalog.FRAME_RATE_DEFAULT) }
        setting { Choice(model, "engine", "Varsayılan motor", listOf("Media3", "VLC")) }
        setting { Choice(model, "fallback", "Codec hatasında VLC", listOf("Açık", "Kapalı")) }
        setting { Choice(model, "buffer", "Media3 tamponu", listOf("Dengeli", "Hızlı", "Kararlı")) }
        setting { Choice(model, "audio", "Ses dili", listOf("Otomatik (Türkçe)", "tr", "en", "de", "fr")) }
        setting { Choice(model, "sub", "Altyazı dili", listOf("Otomatik (Türkçe)", "Kapalı", "tr", "en", "de", "fr")) }
        setting { Choice(model, "size", "Altyazı boyutu", listOf("Orta", "Büyük", "Küçük")) }
        setting { Choice(model, "color", "Altyazı rengi", listOf("Beyaz", "Sarı")) }
        setting {
            Button(onClick = { settingsScope.launch { model.prefs.setShowAdult(!showAdult) } },
                modifier = Modifier.testTag("show-adult")) {
                Text("+18 içerikleri göster · " + if (showAdult) "Açık" else "Kapalı")
            }
        }
        setting {
            Text("Depolama ve yenileme", fontSize = 20.sp)
            Text("${sources.size} kaynak · ${sources.sumOf { it.count }} içerik", color = Ink.muted)
        }
        setting {
            Button(
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
            Button(
                enabled = !busy && sources.isNotEmpty(),
                onClick = {
                    model.job("Kaynaklar ve rehberler yenilendi.") {
                        sources.forEach {
                            model.repo.refresh(it.id)
                            model.repo.refreshGuides(it.id)
                        }
                    }
                },
            ) {
                Text("Şimdi yenile")
            }
        }
        setting {
            Button(
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
        setting {
            SubtitleKeys(model)
        }
        setting {
            Text("Film ve dizi bilgileri", fontSize = 20.sp)
            if (model.repo.tmdbBuiltIn())
                Text("Yerleşik TMDB erişimi var; alan boşsa o kullanılır.", color = Ink.muted)
            Field("TMDB API Read Access Token", token, { token = it }, true)
        }
        setting {
            Button(
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
        setting {
            AsyncImage(R.raw.tmdb, "TMDB", Modifier.width(75.dp).height(14.dp))
            Text(
                "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                fontSize = 12.sp,
                color = Ink.muted,
            )
        }
        setting {
            Text("Uygulama güncellemeleri", fontSize = 20.sp)
            Field("GitHub deposu · sahip/depo", github, { github = it })
            if (github.isBlank())
                Text("Önerilen depo: kudretakn/Lumenara-releases", color = Ink.muted)
        }
        setting {
            Button(
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
                    color = Ink.muted,
                )
                Button(
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
                Button(
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
        if (installation.message.isNotEmpty()) setting { Text(installation.message, color = Ink.accent) }
        setting {
            Button(onClick = { expert = !expert }) {
                Text(if (expert) "Uzman modunu kapat" else "Uzman modu")
            }
        }
        if (expert) {
            setting { Choice(model, "diagnostics", "Yerel olay kaydı", listOf("Kapalı", "Açık")) }
            setting {
                Button(
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
                Text(codecs, fontSize = 13.sp)
            }
            setting {
                Text(log, fontSize = 12.sp)
                Button(
                    onClick = { model.job("Kayıt silindi.") { model.prefs.set("events", "") } }
                ) {
                    Text("Hata kaydını temizle")
                }
            }
            setting {
                Button(
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
        if (notice.isNotEmpty()) setting { Text(notice, color = Ink.accent) }
    }
}
