package dev.lumenara.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class Update(val version: String, val notes: String, val url: String, val sha256: String)

object Updates {
    fun validRepo(text: String) =
        text.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")) && !text.contains("..")

    internal fun version(value: String): List<Int>? {
        val digits = value.trim().removePrefix("v")
        if (!digits.matches(Regex("[0-9]{1,8}(\\.[0-9]{1,8}){1,3}"))) return null
        return digits.split('.').map { it.toInt() } + listOf(0, 0, 0, 0)
    }

    suspend fun check(repository: String, current: String): Update? =
        withContext(Dispatchers.IO) {
            require(validRepo(repository))
            val release =
                Transport.read("https://api.github.com/repos/$repository/releases/latest", false) {
                    input,
                    _ ->
                    JSONObject(input.bufferedReader().readText())
                }
            parseRelease(release, repository, current)
        }

    internal fun parseRelease(release: JSONObject, repository: String, current: String): Update? {
            val tag = release.optString("tag_name")
            val published = version(tag) ?: return null
            val installed = version(current) ?: return null
            val different =
            (0..3).firstOrNull { published[it] != installed[it] } ?: return null
            if (published[different] < installed[different]) return null
            val assets = release.optJSONArray("assets") ?: return null
            val prefix = "https://github.com/$repository/releases/download/$tag/"
            val targets =
                (0 until assets.length()).mapNotNull(assets::optJSONObject).filter {
                    it.optString("browser_download_url").startsWith(prefix, true) &&
                        it.optString("name").endsWith(".apk", true)
                }
            val artifact =
                targets.singleOrNull()
                    ?: targets.singleOrNull { it.optString("name").contains("universal", true) }
                    ?: throw SourceFailure("Sürümde tek bir evrensel APK belirlenemedi.")
            val hash = artifact.optString("digest").removePrefix("sha256:").lowercase()
            if (!hash.matches(Regex("[0-9a-f]{64}")))
                throw SourceFailure("APK için SHA-256 özeti yayımlanmamış.")
            return Update(
                tag,
                release.optString("body").take(6000),
                artifact.getString("browser_download_url"),
                hash,
            )
        }

    @Suppress("DEPRECATION")
    suspend fun verifiedFile(
        context: Context,
        update: Update,
        onVerifying: () -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("release-", ".apk", context.cacheDir)
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                Transport.client(false)
                    .newCall(Request.Builder().url(update.url).build())
                    .execute()
                    .use {
                        if (!it.isSuccessful) throw SourceFailure("APK indirilemedi.")
                        val input = bounded(checkNotNull(it.body).byteStream(), 350L * 1024 * 1024)
                        java.security.DigestInputStream(input, hash).use { stream ->
                            file.outputStream().use { out -> stream.copyTo(out) }
                        }
                    }
                onVerifying()
                if (hash.digest().joinToString("") { "%02x".format(it) } != update.sha256)
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
}
