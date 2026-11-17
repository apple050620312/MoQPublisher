package com.sangege.moqpublisher

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppUpdateInstallerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val file get() = File(context.cacheDir, "update.apk")
    private lateinit var archive: PackageInfo

    @Before
    fun setUpPackages() {
        val installed = packageInfo("1.0.18", 19)
        shadowOf(context.packageManager).installPackage(installed)
        archive = packageInfo("1.0.19", 20)
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, archive)
    }

    private fun packageInfo(version: String, code: Int) = PackageInfo().apply {
        packageName = context.packageName
        versionName = version
        longVersionCode = code.toLong()
        applicationInfo = ApplicationInfo(context.applicationInfo).apply { minSdkVersion = 24 }
        signingInfo = signingInfo("release-certificate")
    }

    private fun signingInfo(certificate: String): SigningInfo = Shadow.newInstanceOf(SigningInfo::class.java).also {
        shadowOf(it).setSignatures(arrayOf(Signature(certificate.toByteArray())))
    }

    private fun inspect() = AppUpdateInstaller.inspect(context, file, "1.0.19")

    @Test
    fun matchingSignedUpdateCanBeInstalled() {
        assertEquals(AppUpdateInstallStatus.READY, inspect())
    }

    @Test
    fun rejectsAnotherPackageBeforeOpeningInstaller() {
        archive.packageName = "com.example.another"
        assertEquals(AppUpdateInstallStatus.PACKAGE_MISMATCH, inspect())
    }

    @Test
    fun rejectsDifferentAndMissingSignatures() {
        archive.signingInfo = signingInfo("another-certificate")
        assertEquals(AppUpdateInstallStatus.SIGNATURE_MISMATCH, inspect())
        archive.signingInfo = null
        assertEquals(AppUpdateInstallStatus.INVALID, inspect())
    }

    @Test
    fun rejectsVersionTagMismatchAndNonIncreasingVersionCode() {
        archive.versionName = "1.0.20"
        assertEquals(AppUpdateInstallStatus.VERSION_MISMATCH, inspect())
        archive.versionName = "1.0.19"
        archive.longVersionCode = 19
        assertEquals(AppUpdateInstallStatus.VERSION_MISMATCH, inspect())
    }

    @Test
    fun explainsWhenAndroidVersionIsTooOld() {
        archive.applicationInfo!!.minSdkVersion = 29
        assertEquals(AppUpdateInstallStatus.UNSUPPORTED_ANDROID, inspect())
    }

    @Test
    fun unparseableArchiveIsRejected() {
        assertEquals(AppUpdateInstallStatus.INVALID,
            AppUpdateInstaller.inspect(context, File(context.cacheDir, "missing.apk"), "1.0.19"))
    }
}
