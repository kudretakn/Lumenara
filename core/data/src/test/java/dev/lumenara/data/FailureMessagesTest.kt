package dev.lumenara.data

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

class FailureMessagesTest {
    @Test fun mapsSslFailuresThroughCauseChain() {
        assertEquals(
            "Güvenli bağlantı kurulamadı. Cihazın tarih ve saatinin doğru olduğundan emin olun.",
            describeFailure(RuntimeException(SSLHandshakeException("handshake"))),
        )
        assertEquals(
            "Güvenli bağlantı kurulamadı. Cihazın tarih ve saatinin doğru olduğundan emin olun.",
            describeFailure(SSLPeerUnverifiedException("peer")),
        )
        assertEquals(
            "Güvenli bağlantı kurulamadı. Cihazın tarih ve saatinin doğru olduğundan emin olun.",
            describeFailure(CertificateException("certificate")),
        )
    }

    @Test fun mapsNetworkAndJsonFailures() {
        assertEquals(
            "Sunucu adı çözülemedi. İnternet bağlantısını ve DNS ayarını kontrol edin.",
            describeFailure(UnknownHostException("host")),
        )
        val connectMessage = "Sunucuya bağlanılamadı. İnternet bağlantısını kontrol edin."
        assertEquals(connectMessage, describeFailure(ConnectException("connect")))
        assertEquals(connectMessage, describeFailure(NoRouteToHostException("route")))
        assertEquals("Sunucudan beklenmeyen bir yanıt geldi.", describeFailure(JSONException("json")))
    }

    @Test fun mapsValidationAndIncludesUnknownType() {
        assertEquals("Depo adı geçersiz (sahip/depo).", describeFailure(IllegalArgumentException("repo")))
        assertEquals(
            "İşlem tamamlanamadı. Adresi, bağlantıyı ve kaynak izinlerini kontrol edin. (IllegalStateException)",
            describeFailure(IllegalStateException("other")),
        )
    }
}
