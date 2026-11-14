package dev.lumenara.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.lumenara.data.Update
import dev.lumenara.data.Updates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

internal data class UpdateInstallState(
    val message: String = "",
    val active: Boolean = false,
    val confirmation: Intent? = null,
)

internal object UpdateInstaller {
    val state = MutableStateFlow(UpdateInstallState())

    fun failed(reason: String) {
        state.value = UpdateInstallState("Kurulamadı: $reason")
    }

    suspend fun install(context: Context, update: Update) {
        if (state.value.active) return
        state.value = UpdateInstallState("İndiriliyor…", active = true)
        try {
            val file = Updates.verifiedFile(context, update) {
                state.value = UpdateInstallState("Doğrulanıyor…", active = true)
            }
            try {
                withContext(Dispatchers.IO) {
                    val installer = context.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(context.packageName)
                    params.setSize(file.length())
                    val id = installer.createSession(params)
                    try {
                        installer.openSession(id).use { session ->
                            session.openWrite("base.apk", 0, file.length()).use { output ->
                                file.inputStream().use { it.copyTo(output) }
                                session.fsync(output)
                            }
                            // PackageInstaller fills in the result extras, so this must be mutable.
                            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                            val callback = PendingIntent.getBroadcast(
                                context, id,
                                Intent(context, UpdateInstallReceiver::class.java), flags,
                            )
                            state.value = UpdateInstallState("Kurulum onayı bekleniyor", active = true)
                            session.commit(callback.intentSender)
                        }
                    } catch (e: Exception) {
                        runCatching { installer.abandonSession(id) }
                        throw e
                    }
                }
            } finally {
                file.delete()
            }
        } catch (e: CancellationException) {
            failed("İşlem iptal edildi.")
            throw e
        } catch (e: Exception) {
            failed(e.localizedMessage ?: "İndirme veya kurulum başlatılamadı.")
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmation == null) UpdateInstaller.failed("Kurulum onayı açılamadı.")
                else UpdateInstaller.state.value = UpdateInstallState(
                    "Kurulum onayı bekleniyor", active = true, confirmation = confirmation,
                )
            }
            PackageInstaller.STATUS_SUCCESS ->
                UpdateInstaller.state.value = UpdateInstallState("Güncelleme kuruldu.")
            else -> UpdateInstaller.failed(
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Kurulum iptal edildi."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Kurulum cihaz tarafından engellendi."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Yüklü paketle çakışma var."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK bu cihazla uyumlu değil."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "APK geçersiz."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Yeterli depolama alanı yok."
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: "Bilinmeyen kurulum hatası."
                }
            )
        }
    }
}
