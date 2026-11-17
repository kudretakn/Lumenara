package dev.lumenara.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class Update(val version: String, val notes: String, val url: String, val sha256: String)

object Updates {
    fun validRepo(text: String) =
        text.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")) && !text.contains("..") && !text.substringAfter('/').endsWith(".")

    internal fun version(value: String): List<Int>? {
        val digits = value.trim().removePrefix("v")
        if (!digits.matches(Regex("[0-9]{1,8}(\\.[0-9]{1,8}){1,3}"))) return null
        return digits.split('.').map { it.toInt() } + listOf(0, 0, 0, 0)
    }

    suspend fun check(repository: String, current: String, preferences: Preferences,
        automatic: Boolean = false): Update? =
        UpdateChecker().check(repository, current, preferences, automatic)

    internal fun newer(tag: String, current: String): Boolean {
        val published = version(tag) ?: return false
        val installed = version(current) ?: return false
        val different = (0..3).firstOrNull { published[it] != installed[it] } ?: return false
        return published[different] > installed[different]
    }

    internal fun parseRelease(release: JSONObject, repository: String, current: String): Update? {
        val tag = release.optString("tag_name")
        if (!newer(tag, current)) return null
        val artifact = artifact(release, repository)
        val hash = digest(artifact) ?: throw missingDigest()
        return Update(tag, release.optString("body").take(6000),
            artifact.getString("browser_download_url"), hash)
    }

    internal fun artifact(release: JSONObject, repository: String): JSONObject {
        val assets = release.optJSONArray("assets") ?: throw missingDigest()
        val prefix = "https://github.com/$repository/releases/download/${release.optString("tag_name")}/"
        val targets = (0 until assets.length()).mapNotNull(assets::optJSONObject).filter {
            it.optString("browser_download_url").startsWith(prefix, true) &&
                it.optString("name").endsWith(".apk", true)
        }
        return targets.singleOrNull()
            ?: targets.singleOrNull { it.optString("name").contains("universal", true) }
            ?: throw SourceFailure("Sürümde tek bir evrensel APK belirlenemedi.")
    }

    internal fun digest(artifact: JSONObject): String? = artifact.optString("digest")
        .lowercase().removePrefix("sha256:").takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    internal fun missingDigest() =
        SourceFailure("Sürüm bulundu ama doğrulama özeti alınamadı; sonra tekrar deneyin.")

    @Suppress("DEPRECATION")
    suspend fun verifiedFile(
        context: Context,
        update: Update,
        onVerifying: () -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("release-", ".apk", context.cacheDir)
            try {
                val digest = downloadApk(update.url, file)
                onVerifying()
                if (digest != update.sha256)
                    throw SourceFailure("İndirilen APK bütünlük denetiminden geçmedi.")
                val pm = context.packageManager
                val flags =
                    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else PackageManager.GET_SIGNATURES
                val installed = pm.getPackageInfo(context.packageName, flags)
                val archive =
                    pm.getPackageArchiveInfo(file.path, flags)
                        ?: throw SourceFailure("Geçersiz APK.")
                fun signers(info: android.content.pm.PackageInfo) =
                    (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                        else info.signatures)
                        ?.toSet()
                        .orEmpty()
                fun code(info: android.content.pm.PackageInfo) =
                    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
                    else info.versionCode.toLong()
                if (
                    archive.packageName != installed.packageName ||
                        code(archive) <= code(installed) ||
                        signers(archive).isEmpty() ||
                        signers(archive) != signers(installed)
                )
                    throw SourceFailure(
                        "APK paketi, sürüm kodu veya imzası yüklü uygulamayla uyumsuz."
                    )
                file
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }

    internal fun downloadApk(url: String, file: File,
        client: OkHttpClient = Transport.client(false)): String {
        val hash = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) throw SourceFailure("APK indirilemedi (HTTP ${it.code})")
            val input = bounded(checkNotNull(it.body).byteStream(), 350L * 1024 * 1024)
            java.security.DigestInputStream(input, hash).use { stream ->
                file.outputStream().use { out -> stream.copyTo(out) }
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
