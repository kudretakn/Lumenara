package dev.lumenara.tv

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class UpgradeSurvivalTest {
    @Test fun encryptedDiskFixtureSurvivesReopenOrApkReplacement() {
        // Host script holds the local fixture server across install -r. With no arguments this is
        // a short ordinary suite test; it never installs an APK or stops the runner's own process.
        val arguments = InstrumentationRegistry.getArguments()
        when (arguments.getString("wp11UpgradePhase")) {
            "seed" -> Wp11UpgradeFixture.seed(arguments.getString("wp11Endpoint") ?: error("WP11 endpoint missing"))
            "verify" -> Wp11RecoveryClient().use { it.command("upgrade", expected = "verified") }
            null -> wp11Server().use { server ->
                Wp11RecoveryClient().use {
                    it.command("seed", endpoint = server.url("/list").toString(), expected = "seeded")
                    it.killAndAwaitDeath()
                }
                Wp11RecoveryClient().use { it.command("upgrade", expected = "verified") }
            }
            else -> error("WP11 unknown upgrade phase")
        }
    }
}
