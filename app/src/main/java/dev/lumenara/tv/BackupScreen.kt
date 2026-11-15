package dev.lumenara.tv

import android.Manifest
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.lumenara.data.BackupPreview
import dev.lumenara.design.*
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*

internal data class LocalBackup(val name: String, val uri: Uri)

/** Only encrypted bytes enter shared storage or the pairing server. */
internal object BackupFiles {
    private const val MAX_BYTES = 16 * 1024 * 1024

    fun filename(): String = "lumenara-yedek-${SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.ROOT).format(Date())}.lmb"

    fun read(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= MAX_BYTES) { "Yedek dosyası çok büyük." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    @Suppress("DEPRECATION")
    fun localBackups(context: Context): List<LocalBackup> {
        if (Build.VERSION.SDK_INT < 29) {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Lumenara")
            return directory.listFiles().orEmpty().filter { it.isFile && it.extension == "lmb" }
                .sortedByDescending { it.lastModified() }.map { LocalBackup(it.name, Uri.fromFile(it)) }
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val result = ArrayList<LocalBackup>()
        context.contentResolver.query(collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/Lumenara/", "%.lmb"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) result.add(LocalBackup(cursor.getString(1), ContentUris.withAppendedId(collection, cursor.getLong(0))))
        }
        return result
    }

    @Suppress("DEPRECATION")
    fun save(context: Context, bytes: ByteArray, filename: String): String {
        require(filename.matches(Regex("lumenara-yedek-[0-9-]+\\.lmb")))
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Lumenara")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Lumenara")
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, filename)
            check(file.createNewFile())
            try { file.outputStream().use { it.write(bytes) } }
            catch (e: Exception) { file.delete(); throw e }
        }
        return "Download/Lumenara/$filename"
    }
}

@Composable
internal fun BackupScreen(model: LibraryModel, restore: Boolean, finishLabel: String = "Ayarlara dön", finish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var encrypted by remember { mutableStateOf<ByteArray?>(null) }
    var filename by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var completed by remember { mutableStateOf(false) }
    var localBackups by remember { mutableStateOf(emptyList<LocalBackup>()) }
    var storageAction by remember { mutableStateOf("save") }
    val modelBusy by model.busy.collectAsState()
    val available = !busy && !modelBusy
    BackHandler { if (!busy) finish() }
    LaunchedEffect(Unit) { first.requestFocus() }

    fun run(action: suspend () -> Unit) {
        if (busy || modelBusy) return
        busy = true
        message = ""
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: dev.lumenara.data.BackupRecoveryException) { message = e.message.orEmpty() }
            catch (e: IllegalArgumentException) {
                message = when (e.message) {
                    "Bu yedek sürümü desteklenmiyor.", "Parola yanlış veya yedek dosyası bozuk.",
                    "Yedek dosyası çok büyük.", "Yedek boyutu sınırı aşıldı." -> e.message.orEmpty()
                    else -> if (restore) "Geri yüklenemedi. Önceki durum korundu. Dosyayı ve parolayı kontrol edin."
                        else "Yedek işlenemedi. Dosyayı ve parolayı kontrol edin."
                }
            } catch (_: Exception) {
                message = if (restore) "Geri yüklenemedi. Önceki durum korundu. Kaynak erişimini, bağlantıyı ve depolama alanını kontrol edip yeniden deneyin."
                    else "Yedek kaydedilemedi. Depolama alanını ve izinleri kontrol edin."
            } finally { busy = false }
        }
    }

    suspend fun prepare(): ByteArray {
        encrypted?.let { return it }
        val chars = password.toCharArray()
        val bytes = try { model.repo.createBackup(chars) } finally { chars.fill('\u0000') }
        encrypted = bytes
        filename = BackupFiles.filename()
        password = ""
        repeatPassword = ""
        return bytes
    }

    val save: () -> Unit = {
        run {
            val bytes = prepare()
            val path = withContext(Dispatchers.IO) { BackupFiles.save(context, bytes, filename) }
            message = "Yedek kaydedildi: $path"
        }
    }
    val listLocal: () -> Unit = {
        run {
            localBackups = withContext(Dispatchers.IO) { BackupFiles.localBackups(context) }
            if (localBackups.isEmpty()) message = "Download/Lumenara klasöründe erişilebilir yedek bulunamadı. Başka bir dosya için belge seçicisini kullanın."
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { if (storageAction == "list") listLocal() else save() }
        else message = "TV depolamasına erişmek için depolama izni gerekli."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selected = uri
        preview = null
        completed = false
        message = ""
    }
    val validPassword = password.length >= 6 && (restore || password == repeatPassword)

    LazyColumn(
        modifier = Modifier.testTag("backup-screen").widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
        contentPadding = PaddingValues(bottom = Spacing.titleGap),
    ) {
        item { Text(if (restore) "Geri yükle" else "Yedekle", style = LumenType.headline) }
        item { Text("Ayarlar, kaynaklar, EPG, Listem, favoriler, izleme ilerlemesi ve son kanallar. Katalog dahil değildir.", color = LumenColors.textSecondary) }
        item {
            Column(Modifier.focusRequester(first).testTag("backup-password")) {
                Field("Yedek parolası · en az 6 karakter", password, {
                    if (available) {
                        password = it; preview = null; encrypted = null; phone = false; completed = false
                    }
                }, true)
            }
        }
        if (!restore) {
            item { Field("Parolayı tekrar girin", repeatPassword, { if (available) repeatPassword = it }, true) }
            item { Text("Parolayı saklayın; unutulursa yedek açılamaz.", color = LumenColors.textSecondary) }
            item {
                LumenButton(style = LumenButtonStyle.Secondary, enabled = available && (validPassword || encrypted != null), onClick = {
                    if (Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        storageAction = "save"
                        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else save()
                }, modifier = Modifier.testTag("backup-save")) { Text("Yedeği kaydet") }
            }
            item {
                LumenButton(style = LumenButtonStyle.Secondary, enabled = available && (validPassword || encrypted != null), onClick = {
                    run { prepare(); phone = true }
                }) { Text("Telefona gönder") }
            }
            if (phone) item { BackupPhonePanel(checkNotNull(encrypted), filename) }
        } else {
            item {
                LumenButton(style = LumenButtonStyle.Secondary, enabled = available, onClick = {
                    if (Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        storageAction = "list"
                        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else listLocal()
                }, modifier = Modifier.testTag("backup-local-files")) { Text("TV’deki yedekleri göster") }
            }
            localBackups.forEach { backup ->
                item {
                    LumenButton(style = LumenButtonStyle.Secondary, enabled = available, onClick = {
                        selected = backup.uri; preview = null; completed = false; message = ""
                    }) { if (selected == backup.uri) LumenIcon(LumenIcon.Check, null)
                        Text(backup.name) }
                }
            }
            item {
                LumenButton(style = LumenButtonStyle.Secondary, enabled = available, onClick = {
                    runCatching { picker.launch(arrayOf("application/octet-stream", "application/json", "*/*")) }
                        .onFailure { message = "Bu TV’de belge seçici bulunamadı." }
                }) { Text(if (selected == null) "Yedek dosyası seç (.lmb)" else "Başka yedek seç") }
            }
            item {
                LumenButton(style = LumenButtonStyle.Secondary, enabled = available && selected != null && validPassword, onClick = {
                    run {
                        val uri = checkNotNull(selected)
                        val bytes = withContext(Dispatchers.IO) {
                            checkNotNull(context.contentResolver.openInputStream(uri)).use(BackupFiles::read)
                        }
                        val chars = password.toCharArray()
                        preview = try { model.repo.previewBackup(bytes, chars) } finally { chars.fill('\u0000') }
                        password = ""
                    }
                }, modifier = Modifier.testTag("backup-preview")) { Text("Yedeği aç ve özeti göster") }
            }
            preview?.let { opened ->
                item { Text(opened.summary, modifier = Modifier.testTag("backup-summary")) }
                item { Text("Ayarlar yedekteki durumuna döner. Aynı kaynağın izleme ve liste kayıtları güncellenir; kaynaklar yenilenir. Devam edilsin mi?", color = LumenColors.textSecondary) }
                item {
                    LumenButton(style = LumenButtonStyle.Secondary, enabled = available && !completed, onClick = {
                        run {
                            val result = model.repo.restoreBackup(opened)
                            message = "Geri yükleme tamamlandı. ${result.skipped} kayıt katalogda bulunamadı; ${result.guideFailures} kaynağın EPG’si yenilenemedi."
                            completed = true
                            preview = null
                        }
                    }, modifier = Modifier.testTag("backup-confirm")) { Text("Onayla ve geri yükle") }
                }
            }
        }
        if (busy) item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                LumenIcon(LumenIcon.Clock, null, tint = LumenColors.textSecondary)
                Text(if (restore) "Yedek işleniyor…" else "Şifreli yedek hazırlanıyor…", style = LumenType.meta)
            }
        }
        if (message.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                val success = completed || message.startsWith("Yedek kaydedildi:")
                LumenIcon(if (success) LumenIcon.Check else LumenIcon.Info, null,
                    tint = if (success) LumenColors.success else LumenColors.error)
                Text(message, style = LumenType.meta, modifier = Modifier.testTag("backup-message"))
            }
        }
        item { LumenButton(style = LumenButtonStyle.Secondary, enabled = !busy, onClick = finish) { Text(finishLabel) } }
    }
}

@Composable
private fun BackupPhonePanel(bytes: ByteArray, filename: String) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<PairSession?>(null) }
    var status by remember { mutableStateOf("Yerel indirme oturumu açılıyor…") }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(bytes, lifecycle) {
        if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@LaunchedEffect
        var opened: PairSession? = null
        try {
            val active = withContext(Dispatchers.IO) { PairSession.create(context).also { opened = it } }
            session = active
            val sent = withContext(Dispatchers.IO) { active.sendBackup(bytes, filename) }
            status = if (sent) "Yedek telefona gönderildi." else "Oturum kapandı. Yeni kod için ekranı yeniden açın."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "İndirme açılamadı. TV ve telefonu aynı yerel ağa bağlayın." }
        finally { opened?.close(); session = null }
    }
    // Closing the socket also interrupts a blocking accept/read when leaving the screen.
    DisposableEffect(lifecycle) { onDispose { session?.close() } }
    val active = session
    Column {
        Text("Telefonun kamerasıyla kodu tarayın; açılan sayfaya TV eşleştirme kodunu girin.", color = LumenColors.textSecondary)
        if (active != null) PairSessionInfo(active) else Text(status, color = LumenColors.accent)
    }
}
