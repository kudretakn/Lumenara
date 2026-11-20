package dev.lumenara.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingFailureMessageTest {
    @Test
    fun mapsFailureReasonsToTurkishMessages() {
        assertEquals(
            "VPN açık. Telefonla eklemek için VPN'i kapatın.",
            pairingFailureMessage(PairingFailureReason.VPN_ACTIVE),
        )
        assertEquals("Ağ bağlantısı yok.", pairingFailureMessage(PairingFailureReason.NO_NETWORK))
        assertEquals(
            "Yerel IPv4 adresi bulunamadı. TV'yi Wi-Fi ya da kabloyla aynı ağa bağlayın.",
            pairingFailureMessage(PairingFailureReason.NO_LOCAL_IPV4),
        )
        assertEquals(
            "Oturum açılamadı. TV ve telefonu aynı yerel ağa bağlayın.",
            pairingFailureMessage(PairingFailureReason.OTHER),
        )
    }
}
