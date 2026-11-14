package dev.streamora.tv

import android.media.MediaCodecList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.imageLoader
import dev.streamora.data.*
import dev.streamora.design.Ink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable
private fun Choice(model: LibraryModel, key: String, label: String, options: List<String>) {
    val current by
        remember(key) { model.prefs.text(key, options.first()) }
            .collectAsStateWithLifecycle(options.first())
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
    val context = LocalContext.current
    val busy by model.busy.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(model.repo.tmdbConfigured()) }
    var expert by remember { mutableStateOf(false) }
    val release by model.update.collectAsStateWithLifecycle()
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
                                    "Streamora · API ${android.os.Build.VERSION.SDK_INT}\n$codecs\nKaynak sayısı: ${sources.size}\n$log"
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
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item {
            Button(onClick = openSources, modifier = Modifier.focusRequester(sourceFocus)) {
                Text("IPTV Kaynakları")
            }
        }
        item { Text("Türkçe   ·   Antrasit tema   ·   Streamora 0.1", color = Ink.muted) }
        item { Text("Oynatıcı", fontSize = 20.sp) }
        item { Choice(model, "engine", "Varsayılan motor", listOf("Media3", "VLC")) }
        item { Choice(model, "fallback", "Codec hatasında VLC", listOf("Açık", "Kapalı")) }
        item { Choice(model, "buffer", "Media3 tamponu", listOf("Dengeli", "Hızlı", "Kararlı")) }
        item { Choice(model, "audio", "Ses dili", listOf("tr", "en", "de", "fr")) }
        item { Choice(model, "sub", "Altyazı dili", listOf("Kapalı", "tr", "en", "de", "fr")) }
        item { Choice(model, "size", "Altyazı boyutu", listOf("Orta", "Büyük", "Küçük")) }
        item { Choice(model, "color", "Altyazı rengi", listOf("Beyaz", "Sarı")) }
        item {
            Text("Depolama ve yenileme", fontSize = 20.sp)
            Text("${sources.size} kaynak · ${sources.sumOf { it.count }} içerik", color = Ink.muted)
        }
        item {
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
        item {
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
        item {
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
        item {
            Text("Film ve dizi bilgileri", fontSize = 20.sp)
            Field("TMDB API Read Access Token", token, { token = it }, true)
        }
        item {
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
        item {
            AsyncImage(R.raw.tmdb, "TMDB", Modifier.width(75.dp).height(14.dp))
            Text(
                "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                fontSize = 12.sp,
                color = Ink.muted,
            )
        }
        item {
            Text("Uygulama güncellemeleri", fontSize = 20.sp)
            Field("GitHub deposu · sahip/depo", github, { github = it })
        }
        item {
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
            item {
                Text("Yeni sürüm · ${info.version}")
                Text(info.notes)
                Button(
                    enabled = !busy,
                    onClick = {
                        runCatching { download.launch("Streamora-${info.version}.apk") }
                            .onFailure {
                                model.notice.value = "Bu TV’de dosya kaydetme hizmeti bulunamadı."
                            }
                    },
                ) {
                    Text("APK’yı doğrula ve kaydet")
                }
            }
        }
        item {
            Button(onClick = { expert = !expert }) {
                Text(if (expert) "Uzman modunu kapat" else "Uzman modu")
            }
        }
        if (expert) {
            item { Choice(model, "diagnostics", "Yerel olay kaydı", listOf("Kapalı", "Açık")) }
            item {
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
            item {
                Text(log, fontSize = 12.sp)
                Button(
                    onClick = { model.job("Kayıt silindi.") { model.prefs.set("events", "") } }
                ) {
                    Text("Hata kaydını temizle")
                }
            }
            item {
                Button(
                    onClick = {
                        runCatching { export.launch("streamora-tanilama.txt") }
                            .onFailure {
                                model.notice.value = "Bu TV’de belge seçici bulunamadı."
                            }
                    }
                ) {
                    Text("Yerel tanılama raporunu kaydet")
                }
            }
        }
        if (notice.isNotEmpty()) item { Text(notice, color = Ink.accent) }
    }
}
