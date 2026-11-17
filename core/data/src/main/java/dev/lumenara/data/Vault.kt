package dev.lumenara.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class Vault(private val context: Context) {
    private val key: SecretKey by lazy { StartupPerf.measure("startup_keystore_key") {
        val alias = "lumenara.catalog.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapping =
            store.getKey(alias, null) as? SecretKey
                ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                    generateKey()
                }
        val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
        val wrapped = prefs.getString("data-key", null)
        if (wrapped == null) {
            val generated = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            check(prefs.edit().putString("data-key", seal(wrapping, generated.encoded)).commit())
            generated
        } else SecretKeySpec(open(wrapping, wrapped), "AES")
    } }

    private fun seal(key: SecretKey, bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.iv + cipher.doFinal(bytes), Base64.NO_WRAP)
    }

    private fun open(key: SecretKey, text: String): ByteArray {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    fun encrypt(text: String) = seal(key, text.toByteArray(Charsets.UTF_8))

    fun decrypt(text: String) = open(key, text).toString(Charsets.UTF_8)
}
