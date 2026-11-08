package dev.streamora.tv

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNavigationTest {
    @Test
    fun backFromSetupOpensHome() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val device = UiDevice.getInstance(instrument)
        assertTrue(device.wait(Until.hasObject(By.text("IPTV Kaynakları")), 5000))
        device.pressDPadRight()
        device.pressDPadCenter()
        assertTrue(device.wait(Until.hasObject(By.text("M3U dosyası seç")), 3000))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("Ana Sayfa")), 3000))
    }
}
