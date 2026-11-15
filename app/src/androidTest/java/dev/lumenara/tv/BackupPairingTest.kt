package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.Repository
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class BackupPairingTest {
    @Test fun downloadRequiresCodeAndOriginAndReturnsOnlyEncryptedBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = runBlocking { Repository.get(context).createBackup("parola-123".toCharArray()) }
        PairSession(InetAddress.getByName("127.0.0.1") as Inet4Address).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val task = executor.submit<Boolean> {
                try { server.sendBackup(bytes, "lumenara-yedek-2026-11-15.lmb") }
                finally { server.close() }
            }
            try {
                val trust = object : X509TrustManager {
                    override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                    override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) = throw CertificateException()
                    override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) {
                        val fingerprint = MessageDigest.getInstance("SHA-256").digest(chain.first().encoded)
                            .joinToString(":") { "%02X".format(it) }
                        if (fingerprint != server.fingerprint) throw CertificateException()
                    }
                }
                val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                val client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).build()
                fun post(code: String, origin: String = server.url) = client.newCall(
                    Request.Builder().url(server.url).header("Origin", origin)
                        .post(FormBody.Builder().add("code", code).build()).build()
                ).execute()
                client.newCall(Request.Builder().url(server.url).build()).execute().use {
                    assertEquals(200, it.code)
                    assertTrue(it.body!!.string().contains("TV eşleştirme kodu"))
                    assertNull(it.header("Content-Disposition"))
                }
                post("wrong").use { assertEquals(403, it.code) }
                post(server.code, "https://untrusted.invalid").use { assertEquals(404, it.code) }
                post(server.code).use {
                    assertEquals(200, it.code)
                    assertEquals("application/octet-stream", it.header("Content-Type"))
                    assertTrue(it.header("Content-Disposition")!!.contains("lumenara-yedek-2026-11-15.lmb"))
                    assertArrayEquals(bytes, it.body!!.bytes())
                }
                assertTrue(task.get(5, TimeUnit.SECONDS))
                assertTrue(runCatching { post(server.code).close() }.isFailure)
            } finally { server.close(); executor.shutdownNow() }
        }
    }
}
