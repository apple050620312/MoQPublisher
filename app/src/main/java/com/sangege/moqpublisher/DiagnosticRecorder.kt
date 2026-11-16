package com.sangege.moqpublisher

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded, restart-persistent event history. No camera or microphone payloads are recorded. */
internal class DiagnosticRecorder(
    private val directory: File,
    private val maxFileBytes: Int = 256 * 1024,
    private val fileCount: Int = 4,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        require(maxFileBytes >= 128 && fileCount >= 1)
    }

    @Synchronized
    fun record(event: String, detail: String = "") {
        // Redact before persisting, including error messages originating in native code.
        val prefix = "${dateFormat.format(Date(nowMs()))} ${event.replace(Regex("[\\r\\n]"), " ")} "
        val line = DiagnosticRedactor.redact(prefix + detail)
            .replace("\r", "\\r").replace("\n", "\\n")
        val encoded = (line.take(8_000) + "\n").toByteArray(Charsets.UTF_8)
        val bytes = if (encoded.size <= maxFileBytes) encoded else {
            // Keep complete UTF-8 characters even with a deliberately tiny test/storage limit.
            var short = line.take(maxFileBytes / 4)
            while ((short + "\n").toByteArray(Charsets.UTF_8).size > maxFileBytes) short = short.dropLast(1)
            (short + "\n").toByteArray(Charsets.UTF_8)
        }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostic history" }
        val current = file(0)
        if (current.length() + bytes.size > maxFileBytes) {
            check(!file(fileCount - 1).exists() || file(fileCount - 1).delete()) { "Cannot rotate diagnostic history" }
            for (index in fileCount - 2 downTo 0) {
                val source = file(index)
                check(!source.exists() || source.renameTo(file(index + 1))) { "Cannot rotate diagnostic history" }
            }
        }
        current.appendBytes(bytes)
    }

    /** Take a consistent copy while logging is locked; compression happens after releasing it. */
    @Synchronized
    private fun history(): String = (fileCount - 1 downTo 0)
        .map { file(it) }.filter { it.isFile }.joinToString("") { it.readText(Charsets.UTF_8) }

    fun export(destination: File, summary: String, processLog: String) {
        val events = history()
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                val entries = linkedMapOf(
                    "README.txt" to """
                        MoQPublisher diagnostic report
                        All event timestamps are UTC. History survives app restarts and rotates at 1 MiB.
                        metrics entries are interval counts/FPS using actual elapsed time, not target FPS.
                        pixel_copy, encoder_input, encoder_drain and moq_write timings isolate processing costs.
                        Camera/encoder callbacks and MoQ publication run on separate workers;
                        video_handoff_ms includes bounded-queue wait plus native publication time.
                        network_capacity_bps is an estimate; send_kbps is measured transport throughput.
                        CPU percent uses one logical core as 100 percent and may exceed 100 percent.
                        app-logcat.txt contains a best-effort snapshot of this process, not other apps.
                        Query values, authorization credentials and JWTs are redacted before export.
                        No video, audio, account preferences or microphone samples are included.
                        This report covers the broadcaster; the viewing browser's logs are separate.
                    """.trimIndent() + "\n",
                    "summary.txt" to summary,
                    "events.log" to events,
                    "app-logcat.txt" to processLog
                )
                for ((name, contents) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(DiagnosticRedactor.redact(contents).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    private fun file(index: Int) = File(directory, "events.$index.log")
}

internal object DiagnosticRedactor {
    private val activeSecrets = ArrayDeque<String>()
    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s<>"']+""")
    private val userInfo = Regex("""(?i)(://)[^/\s]*@""")
    private val namedSecret = Regex("""(?i)(\b(?:jwt|token|publish_?token|subscribe_?token|access_token|refresh_token|auth_token|authorization|password|signature|secret)\b["']?\s*[:=]\s*["']?(?:Bearer\s+)?)[^\s&;,}"']+""")
    private val pathCredential = Regex("""(?i)((?:https?|wss?|quic)://(?:[a-z0-9-]+\.cloudflare\.mediaoverquic\.com|moq\.watch)(?::\d+)?)/[^\s<>"'?#]*""")
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")

    @Synchronized fun protectSecrets(vararg values: String) {
        for (value in values.map(String::trim).filter { it.length >= 8 }) {
            activeSecrets.remove(value)
            activeSecrets.addLast(value)
            while (activeSecrets.size > 16) activeSecrets.removeFirst()
        }
    }

    fun maskPathCredentials(text: String): String = pathCredential.replace(text) { "${it.groupValues[1]}/[redacted]" }

    fun redact(text: String): String {
        val withoutQueries = url.replace(maskPathCredentials(text)) { match ->
            val value = match.value
            val base = value.substringBefore('?').substringBefore('#')
            userInfo.replace(base) { "${it.groupValues[1]}[redacted]@" } +
                (if ('?' in value) "?[redacted]" else "") +
                (if ('#' in value) "#[redacted]" else "")
        }
        val withoutSecrets = namedSecret.replace(withoutQueries) { "${it.groupValues[1]}[redacted]" }
        var result = jwt.replace(bearer.replace(withoutSecrets, "Bearer [redacted]"), "[redacted-jwt]")
        val secrets = synchronized(this) { activeSecrets.toList().sortedByDescending(String::length) }
        for (secret in secrets) result = result.replace(secret, "[redacted]")
        return result
    }
}
