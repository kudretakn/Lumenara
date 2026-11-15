package dev.lumenara.data

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.json.JSONException

/** Converts common failures into short, actionable messages for the user. */
fun describeFailure(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }.toList()
    val message = when {
        causes.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException || it is CertificateException } ->
            "Güvenli bağlantı kurulamadı. Cihazın tarih ve saatinin doğru olduğundan emin olun."
        causes.any { it is UnknownHostException } ->
            "Sunucu adı çözülemedi. İnternet bağlantısını ve DNS ayarını kontrol edin."
        causes.any { it is ConnectException || it is NoRouteToHostException } ->
            "Sunucuya bağlanılamadı. İnternet bağlantısını kontrol edin."
        causes.any { it is JSONException } -> "Sunucudan beklenmeyen bir yanıt geldi."
        causes.any { it is IllegalArgumentException } -> "Depo adı geçersiz (sahip/depo)."
        else -> null
    }
    return message ?: "İşlem tamamlanamadı. Adresi, bağlantıyı ve kaynak izinlerini kontrol edin. (${e.javaClass.simpleName})"
}
