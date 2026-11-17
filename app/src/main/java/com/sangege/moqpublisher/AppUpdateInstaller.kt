package com.sangege.moqpublisher

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.security.MessageDigest

enum class AppUpdateInstallStatus {
    READY, MISSING, INVALID, PACKAGE_MISMATCH, SIGNATURE_MISMATCH, UNSUPPORTED_ANDROID, VERSION_MISMATCH;

    fun errorMessage(): UiMessage? = when (this) {
        READY -> null
        MISSING -> UiMessage(R.string.app_update_missing_file)
        INVALID -> UiMessage(R.string.app_update_invalid_apk)
        PACKAGE_MISMATCH -> UiMessage(R.string.app_update_package_mismatch)
        SIGNATURE_MISMATCH -> UiMessage(R.string.app_update_signature_mismatch)
        UNSUPPORTED_ANDROID -> UiMessage(R.string.app_update_android_unsupported)
        VERSION_MISMATCH -> UiMessage(R.string.app_update_version_mismatch)
    }
}

internal object AppUpdateInstaller {
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun intent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME_TYPE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Carry the grant through installers that forward the intent to another Activity.
        clipData = ClipData.newRawUri("MoQ Publisher update", uri)
    }

    @Suppress("DEPRECATION")
    fun inspect(context: Context, file: File, expectedVersion: String): AppUpdateInstallStatus {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val archive = runCatching { manager.getPackageArchiveInfo(file.absolutePath, flags) }
            .getOrNull() ?: return AppUpdateInstallStatus.INVALID
        if (archive.packageName != context.packageName) return AppUpdateInstallStatus.PACKAGE_MISMATCH
        if ((archive.applicationInfo?.minSdkVersion ?: 0) > Build.VERSION.SDK_INT) {
            return AppUpdateInstallStatus.UNSUPPORTED_ANDROID
        }
        val installed = runCatching { manager.getPackageInfo(context.packageName, flags) }
            .getOrNull() ?: return AppUpdateInstallStatus.INVALID
        if (archive.versionName != expectedVersion || versionCode(archive) <= versionCode(installed)) {
            return AppUpdateInstallStatus.VERSION_MISMATCH
        }
        val archiveSigners = signingDigests(archive)
        val installedSigners = signingDigests(installed)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty()) return AppUpdateInstallStatus.INVALID
        if (archiveSigners != installedSigners) return AppUpdateInstallStatus.SIGNATURE_MISMATCH
        return AppUpdateInstallStatus.READY
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signingDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty()
        else info.signatures.orEmpty()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
    }
}
