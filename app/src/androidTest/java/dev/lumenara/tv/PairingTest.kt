package dev.lumenara.tv

import java.net.*
import java.security.MessageDigest
import java.security.cert.*
import java.util.concurrent.*
import javax.net.ssl.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    @Test
    fun pinsLocalCertificateAndConsumesCodeOnce() {
        PairSession(InetAddress.getByName("127.0.0.1") as Inet4Address).use { server ->
            val task = Executors.newSingleThreadExecutor()
            val session =
                task.submit<Paired?> {
                    try {
                        server.receive()
                    } finally {
                        server.close()
                    }
                }
            try {
                val trust =
                    object : X509TrustManager {
                        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                        override fun checkClientTrusted(
                            chain: Array<X509Certificate>,
                            type: String,
                        ) = throw CertificateException()

                        override fun checkServerTrusted(
                            chain: Array<X509Certificate>,
                            type: String,
                        ) {
                            val actual =
                                MessageDigest.getInstance("SHA-256")
                                    .digest(chain.first().encoded)
                                    .joinToString(":") { "%02X".format(it) }
                            if (actual != server.fingerprint) throw CertificateException()
                        }
                    }
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                val client =
                    OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).build()
                fun submit(code: String): Int {
                    val form =
                        FormBody.Builder()
                            .add("name", "Test")
                            .add("address", "https://example.invalid/list")
                            .add("code", code)
                            .build()
                    return client
                        .newCall(
                            Request.Builder()
                                .url(server.url)
                                .header("Origin", server.url)
                                .post(form)
                                .build()
                        )
                        .execute()
                        .use { it.code }
                }
                assertEquals(403, submit("wrong"))
                assertEquals(200, submit(server.code))
                assertEquals("Test", session.get(5, TimeUnit.SECONDS)?.name)
                assertTrue(runCatching { submit(server.code) }.isFailure)
            } finally {
                server.close()
                task.shutdownNow()
            }
        }
    }
}
