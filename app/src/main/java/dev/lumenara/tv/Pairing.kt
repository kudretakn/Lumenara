package dev.lumenara.tv

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.lumenara.data.SourceSpec
import dev.lumenara.design.Ink
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

internal data class Paired(val name: String, val spec: SourceSpec)

internal class PairSession(address: Inet4Address) : Closeable {
    private val host = address.hostAddress!!
    private val cert =
        HeldCertificate.Builder()
            .commonName("Lumenara")
            .addSubjectAlternativeName(host)
            .duration(10, TimeUnit.MINUTES)
            .build()
    private val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
    private val listener =
        tls.sslContext().serverSocketFactory.createServerSocket(0, 2, address) as SSLServerSocket
    val url = "https://$host:${listener.localPort}"
    val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
    val fingerprint =
        MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString(":") {
            "%02X".format(it)
        }
    private val start = TimeSource.Monotonic.markNow()
    @Volatile private var client: Socket? = null
    private val expiry =
        java.util.Timer("lumenara-pair-expiry", true).apply {
            schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        close()
                    }
                },
                180000,
            )
        }

    override fun close() {
        expiry.cancel()
        listener.close()
        client?.close()
    }

    fun receive(): Paired? {
        var result: Paired? = null
        exchange(null, "") { result = it }
        return result
    }

    fun sendBackup(bytes: ByteArray, filename: String): Boolean {
        require(filename.matches(Regex("lumenara-yedek-[0-9-]+\\.lmb")))
        return exchange(bytes, filename) { }
    }

    private fun exchange(backup: ByteArray?, filename: String, paired: (Paired) -> Unit): Boolean {
        listener.soTimeout = 1000
        var failures = 0
        while (!listener.isClosed && start.elapsedNow() < 3.minutes && failures < 5) {
            val socket =
                try {
                    listener.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
            socket.use {
                client = socket
                socket.soTimeout = 3500
                try {
                    val input = socket.getInputStream().buffered()
                    var bytes = 0
                    fun line(): String {
                        val line = StringBuilder()
                        while (bytes++ < 8192) {
                            val b = input.read()
                            if (b < 0) throw EOFException()
                            if (b == 10) return line.toString().trimEnd('\r')
                            line.append(b.toChar())
                        }
                        throw IOException("İstek çok uzun")
                    }
                    val method = line()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] =
                            header.substringAfter(':').trim()
                    }
                    fun reply(status: String, html: String) {
                        val data = html.toByteArray()
                        val head =
                            "HTTP/1.1 $status\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${data.size}\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; form-action 'self'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(head.toByteArray())
                            write(data)
                            flush()
                        }
                    }
                    if (headers["host"] != url.removePrefix("https://"))
                        reply("403 Forbidden", "Erişim reddedildi.")
                    else if (method == "GET / HTTP/1.1") reply("200 OK", if (backup == null) FORM else DOWNLOAD_FORM)
                    else if (
                        method == "POST / HTTP/1.1" &&
                            headers["origin"] == url &&
                            headers["content-type"]?.substringBefore(';') ==
                                "application/x-www-form-urlencoded"
                    ) {
                        val size = headers["content-length"]?.toIntOrNull()
                        if (size == null || size !in 1..8192)
                            reply("400 Bad Request", "Geçersiz form.")
                        else {
                            val data = ByteArray(size)
                            DataInputStream(input).readFully(data)
                            val fields =
                                data.toString(Charsets.UTF_8).split('&').associate {
                                    it.substringBefore('=') to
                                        URLDecoder.decode(it.substringAfter('='), "UTF-8")
                                }
                            if (start.elapsedNow() >= 3.minutes || fields["code"] != code) {
                                failures++
                                reply("403 Forbidden", "Kod geçersiz veya süresi doldu.")
                            } else if (backup != null) {
                                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                                    "Content-Disposition: attachment; filename=\"$filename\"\r\n" +
                                    "Content-Length: ${backup.size}\r\nCache-Control: no-store\r\n" +
                                    "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
                                socket.getOutputStream().apply {
                                    write(head.toByteArray(Charsets.US_ASCII))
                                    write(backup)
                                    flush()
                                }
                                return true
                            } else {
                                val name = fields["name"].orEmpty().trim()
                                val address = fields["address"].orEmpty().trim()
                                val spec =
                                    SourceSpec(
                                        address,
                                        fields["http"] == "on",
                                        fields["user"].orEmpty(),
                                        fields["password"].orEmpty(),
                                    )
                                if (
                                    name.isEmpty() ||
                                        !(address.startsWith("https://") ||
                                            spec.allowHttp && address.startsWith("http://"))
                                ) {
                                    reply(
                                        "400 Bad Request",
                                        "Liste adını, adresi ve HTTP seçimini kontrol edin.",
                                    )
                                } else {
                                    reply(
                                        "200 OK",
                                        "Kaynak TV’ye aktarıldı. Bağlantı sonucunu TV üzerinden kontrol edin.",
                                    )
                                    paired(Paired(name, spec))
                                    return true
                                }
                            }
                        }
                    } else reply("404 Not Found", "Bulunamadı.")
                } catch (_: IOException) {
                    /* Aborted or timed-out browser request. */
                } catch (_: IllegalArgumentException) {
                    /* Malformed form encoding. */
                } finally {
                    client = null
                }
            }
        }
        return false
    }

    companion object {
        private const val DOWNLOAD_FORM =
            """<!doctype html><html lang="tr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Lumenara yedek</title>
            <h1>Lumenara yedeği</h1><form method="post"><p><label>TV eşleştirme kodu <input name="code" required inputmode="numeric"></label></p>
            <p>Dosyayı açmak için TV’de belirlediğiniz yedek parolası gerekir.</p><button>Şifreli yedeği indir</button></form></html>"""
        private const val FORM =
            """<!doctype html><html lang="tr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Lumenara</title>
        <h1>Lumenara</h1><form method="post"><p><label>TV eşleştirme kodu <input name="code" required inputmode="numeric"></label></p>
        <p><label>Liste adı <input name="name" required></label></p><p><label>M3U veya Xtream sunucu adresi <input type="url" name="address" required></label></p>
        <p><label>Xtream kullanıcı adı <input name="user"></label></p><p><label>Xtream şifre <input type="password" name="password"></label></p>
        <p><label><input type="checkbox" name="http"> Bu kaynak için HTTP erişimine izin ver</label></p><button>TV’ye aktar</button></form></html>"""

        fun create(context: Context): PairSession {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            require(
                caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            )
            val ip =
                cm.getLinkProperties(network)
                    ?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress } ?: error("Yerel adres yok")
            return PairSession(ip)
        }
    }
}

@Composable
internal fun PairPanel(model: LibraryModel, finish: () -> Unit) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<PairSession?>(null) }
    var status by remember { mutableStateOf("Yerel oturum açılıyor…") }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) {
        if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@LaunchedEffect
        var opened: PairSession? = null
        try {
            val active =
                withContext(Dispatchers.IO) { PairSession.create(context).also { opened = it } }
            session = active
            val paired = withContext(Dispatchers.IO) { active.receive() }
            if (paired != null) {
                model.job("Telefon kaynağı eklendi.") {
                    model.repo.add(paired.name, paired.spec)
                    finish()
                }
                status = "Kaynak sınanıyor…"
            } else status = "Oturum kapandı. Yeni bir kod için yeniden açın."
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = "Oturum açılamadı. TV ve telefonu aynı yerel ağa bağlayın."
        } finally {
            opened?.close()
            session = null
        }
    }
    DisposableEffect(lifecycle) { onDispose { session?.close() } }
    val active = session
    if (active == null) Text(status, color = Ink.accent)
    else PairSessionInfo(active)
}

@Composable
internal fun PairSessionInfo(active: PairSession) {
        val qr =
            remember(active.url) {
                val bits = QRCodeWriter().encode(active.url, BarcodeFormat.QR_CODE, 192, 192)
                Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                    .apply {
                        val pixels =
                            IntArray(192 * 192) {
                                if (bits[it % 192, it / 192]) android.graphics.Color.BLACK
                                else android.graphics.Color.WHITE
                            }
                        setPixels(pixels, 0, 192, 0, 0, 192, 192)
                    }
                    .asImageBitmap()
            }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(qr, "Yerel kurulum QR kodu", Modifier.size(108.dp))
            Column {
                Text(active.url)
                Text("Kod · ${active.code}   (3 dakika)", fontSize = 20.sp)
                Text("Telefonda yerel sertifikanın SHA-256 izini doğrulayın.", fontSize = 12.sp)
                Text(active.fingerprint, fontSize = 11.sp, color = Ink.muted)
            }
        }
}
