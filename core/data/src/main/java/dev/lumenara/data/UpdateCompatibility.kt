package dev.lumenara.data

internal enum class UpdateMismatch(val message: String) {
    PACKAGE("APK paket adı farklı."),
    VERSION_CODE("APK sürüm kodu eski veya yüklü sürümle aynı."),
    SIGNATURE("APK imzası farklı."),
}

/** Empty signer sets mean unavailable metadata; PackageInstaller must enforce compatibility. */
internal fun <T> updateMismatch(
    packageSame: Boolean,
    archiveCode: Long,
    installedCode: Long,
    archiveSigners: Set<T>,
    installedSigners: Set<T>,
): UpdateMismatch? = when {
    !packageSame -> UpdateMismatch.PACKAGE
    archiveCode <= installedCode -> UpdateMismatch.VERSION_CODE
    archiveSigners.isNotEmpty() && installedSigners.isNotEmpty() &&
        archiveSigners != installedSigners -> UpdateMismatch.SIGNATURE
    else -> null
}
