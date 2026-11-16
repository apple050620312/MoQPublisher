package com.sangege.moqpublisher

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticRecorderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun rotationBoundsStorageAndRetainsRecentHistoryAcrossRestarts() {
        val history = temporary.newFolder("history")
        val recorder = DiagnosticRecorder(history, maxFileBytes = 256, fileCount = 3)
        repeat(60) { recorder.record("sample", "sequence=$it " + "x".repeat(40)) }
        assertTrue(history.listFiles()!!.sumOf { it.length() } <= 768L)
        val restarted = DiagnosticRecorder(history, maxFileBytes = 256, fileCount = 3)
        restarted.record("restart", "new process")
        val report = temporary.newFile("report.zip")
        restarted.export(report, "summary", "process")
        val events = entries(report).getValue("events.log")
        assertFalse(events.contains("sequence=0 "))
        assertTrue(events.contains("sequence=59 "))
        assertTrue(events.indexOf("sequence=59 ") < events.indexOf("new process"))
        assertTrue(history.listFiles()!!.all { it.length() <= 256L })
    }

    @Test
    fun credentialsAreAbsentFromBothStoredEventsAndAllExportEntries() {
        val history = temporary.newFolder("history")
        val recorder = DiagnosticRecorder(history)
        val sensitive = "https://user:pass@relay.example/live?jwt=SECRET_QUERY auth_token=SECRET_FIELD Authorization: Bearer SECRET_HEADER"
        recorder.record("connection_error", sensitive)
        assertFalse(history.listFiles()!!.single().readText().contains("SECRET_"))
        val report = temporary.newFile("report.zip")
        recorder.export(report, sensitive, "native error: $sensitive")
        val content = entries(report)
        assertEquals(setOf("README.txt", "summary.txt", "events.log", "app-logcat.txt"), content.keys)
        for (text in content.values) {
            assertFalse(text.contains("SECRET_"))
            assertFalse(text.contains("user:pass"))
        }
        assertTrue(content.getValue("events.log").contains("relay.example/live"))
    }

    @Test
    fun oversizedUnicodeErrorsRemainBoundedAndReadable() {
        val history = temporary.newFolder("history")
        val recorder = DiagnosticRecorder(history, maxFileBytes = 512, fileCount = 2)
        recorder.record("error", "編碼器異常\n".repeat(10_000))
        val bytes = history.listFiles()!!.single().readBytes()
        assertTrue(bytes.size <= 512)
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains('\uFFFD'))
        assertEquals(1, text.count { it == '\n' })
    }

    @Test
    fun exportingWhileCaptureThreadsRecordProducesAValidSnapshot() {
        val recorder = DiagnosticRecorder(temporary.newFolder("history"))
        val executor = Executors.newFixedThreadPool(3)
        try {
            val writes = (0..1).map { thread ->
                executor.submit(Callable { repeat(200) { recorder.record("capture", "thread=$thread sequence=$it") } })
            }
            val snapshots = executor.submit(Callable {
                repeat(5) { index ->
                    val report = File(temporary.root, "concurrent-$index.zip")
                    recorder.export(report, "state", "process")
                    assertEquals(4, entries(report).size)
                }
            })
            writes.forEach { it.get() }
            snapshots.get()
            val report = temporary.newFile("final.zip")
            recorder.export(report, "state", "process")
            assertEquals(400, entries(report).getValue("events.log").lineSequence().count { it.contains(" capture ") })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun entries(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
