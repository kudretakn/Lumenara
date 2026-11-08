package dev.streamora.tv

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.media3.common.*
import androidx.tv.material3.*
import dev.streamora.design.Ink

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
