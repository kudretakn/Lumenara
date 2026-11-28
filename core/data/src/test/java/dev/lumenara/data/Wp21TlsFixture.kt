package dev.lumenara.data

import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** Local-only identity, valid 2020–2120. Trust only this certificate; never disable hostname checks. */
internal class Wp21TlsFixture {
    private val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(Base64.getMimeDecoder().decode(CERTIFICATE).inputStream())
    private val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        val key = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(PRIVATE_KEY)))
        setKeyEntry("wp21-localhost", key, charArrayOf(), arrayOf(certificate))
    }
    private val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
        init(store, charArrayOf())
    }
    private val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("wp21-localhost", certificate)
        })
    }
    private val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
    val serverSocketFactory get() = context.socketFactory

    fun client(allowHttp: Boolean): OkHttpClient = Transport.client(allowHttp).newBuilder()
        .sslSocketFactory(context.socketFactory, trust.trustManagers.single() as X509TrustManager)
        .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private companion object {
        // Deliberately public test key; this identity is used only by loopback MockWebServer.
        const val PRIVATE_KEY = """
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgw2G+OisBefsR6643D9mznahcRX2xKVuW9r56INykrLihRANCAARz
fZ/vWjLSoDQ4hJqHa/ncC3rddnL9l8cBXkNvvWlgQ4Xqq1oOAIB9Sm7lX6+NMf6RjLDWRPHlEC329DlkV+Sl
        """
        const val CERTIFICATE = """
MIIBWzCCAQGgAwIBAgIBFTAKBggqhkjOPQQDAjAmMSQwIgYDVQQDDBtXUDIxIGxvY2FsaG9zdCB0ZXN0IGZpeHR1cmUwIBcNMjAw
MTAxMDAwMDAwWhgPMjEyMDAxMDEwMDAwMDBaMCYxJDAiBgNVBAMMG1dQMjEgbG9jYWxob3N0IHRlc3QgZml4dHVyZTBZMBMGByqG
SM49AgEGCCqGSM49AwEHA0IABHN9n+9aMtKgNDiEmodr+dwLet12cv2XxwFeQ2+9aWBDheqrWg4AgH1KbuVfr40x/pGMsNZE8eUQ
Lfb0OWRX5KWjHjAcMBoGA1UdEQQTMBGCCWxvY2FsaG9zdIcEfwAAATAKBggqhkjOPQQDAgNIADBFAiEAwg+GXPfqBcF4i3EChAzj
7cgXJzuvodJFsV/3SIB14LkCIGuE9nlGqn3XQ14CccH/Z2onDicpyalzrfglgRv7T4k1
        """
    }
}
