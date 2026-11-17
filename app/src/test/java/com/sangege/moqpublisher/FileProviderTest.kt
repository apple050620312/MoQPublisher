package com.sangege.moqpublisher

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FileProviderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun updateAndDiagnosticProvidersHaveDifferentAndroidComponentNames() {
        val updates = context.packageManager.resolveContentProvider("${context.packageName}.updates", PackageManager.GET_META_DATA)
        val diagnostics = context.packageManager.resolveContentProvider("${context.packageName}.diagnostics", PackageManager.GET_META_DATA)
        assertNotNull(updates)
        assertNotNull(diagnostics)
        // Android caches local providers by package + class, not by authority.
        assertNotEquals(updates!!.name, diagnostics!!.name)
        assertFalse(updates.exported)
        assertTrue(updates.grantUriPermissions)
    }

    @Test
    fun installIntentCarriesTheApkAndReadGrantThroughForwardedActivities() {
        val uri = Uri.parse("content://${context.packageName}.updates/app_updates/update.apk")
        val intent = AppUpdateInstaller.intent(uri)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
