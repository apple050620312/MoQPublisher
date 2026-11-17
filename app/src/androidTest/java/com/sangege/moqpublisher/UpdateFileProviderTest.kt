package com.sangege.moqpublisher

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android URI/file opening; Robolectric on Windows has different path separators. */
@RunWith(AndroidJUnit4::class)
class UpdateFileProviderTest {
    @Test
    fun updateAndDiagnosticsUrisBothOpenTheirOwnFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = listOf(
            Triple("updates", "app-update/provider-test.apk", "application/vnd.android.package-archive"),
            Triple("diagnostics", "diagnostic-exports/provider-test.zip", "application/zip")
        )
        for ((authority, path, mime) in files) {
            val file = File(context.cacheDir, path)
            file.parentFile!!.mkdirs()
            val bytes = ByteArray(131_073) { (it % 251).toByte() }
            file.writeBytes(bytes)
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.$authority", file)
                assertEquals("content", uri.scheme)
                assertEquals(mime, context.contentResolver.getType(uri))
                context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
                context.contentResolver.openFileDescriptor(uri, "r")!!.use { assertEquals(file.length(), it.statSize) }
            } finally {
                file.delete()
            }
        }
    }
}
