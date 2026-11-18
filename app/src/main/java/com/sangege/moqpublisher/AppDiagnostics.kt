package com.sangege.moqpublisher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

enum class BroadcastNetwork(val label: Int) {
    WIFI(R.string.network_wifi),
    CELLULAR(R.string.network_cellular),
    ETHERNET(R.string.network_ethernet),
    VPN(R.string.network_vpn),
    OFFLINE(R.string.network_offline),
    UNKNOWN(R.string.stat_unavailable)
}

/** App-owned diagnostics, usable on a phone without adb, root or storage permissions. */
class AppDiagnostics(context: Context) {
    private val context = context.applicationContext
    private val recorder = DiagnosticRecorder(File(this.context.noBackupFilesDir, "diagnostics"))
    internal val metrics = StreamMetrics()
    private val exportLock = Any()
    private var previousCpuMs = Process.getElapsedCpuTime()
    private var previousSampleMs = SystemClock.elapsedRealtime()

    init {
        event("app_process_start", mapOf(
            "version" to version(), "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT, "pid" to Process.myPid()
        ))
    }

    internal fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        record(name, fields.entries.joinToString(" ") { "${it.key}=${it.value ?: "--"}" })
    }

    internal fun error(name: String, error: Throwable) = record(name, error.stackTraceToString())

    private fun record(name: String, message: String) {
        // Storage errors must never interrupt camera, audio or teardown.
        runCatching { recorder.record(name, message) }.onFailure {
            Log.w("AppDiagnostics", "Cannot write diagnostic history: ${it.javaClass.simpleName}")
        }
    }

    @Synchronized
    internal fun performance(): Map<String, Any?> {
        val elapsedMs = SystemClock.elapsedRealtime()
        val cpuMs = Process.getElapsedCpuTime()
        val intervalMs = elapsedMs - previousSampleMs
        val cpuPct = if (intervalMs > 0) (cpuMs - previousCpuMs) * 100.0 / intervalMs else null
        previousSampleMs = elapsedMs
        previousCpuMs = cpuMs
        val runtime = Runtime.getRuntime()
        val power = context.getSystemService(PowerManager::class.java)
        return linkedMapOf(
            "process_cpu_pct" to cpuPct?.let { String.format(Locale.US, "%.1f", it) },
            "java_heap_bytes" to runtime.totalMemory() - runtime.freeMemory(),
            "native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),
            "thermal_status" to if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null,
            "power_save" to power?.isPowerSaveMode
        )
    }

    /** Called on Dispatchers.IO. The returned URI grants access to only this exported file. */
    internal fun export(snapshot: Map<String, Any?>): Uri = synchronized(exportLock) {
        event("diagnostics_export", snapshot)
        val directory = File(context.cacheDir, "diagnostic-exports")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create export directory" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val destination = File(directory, "MoQPublisher-${version()}-diagnostics-$stamp.zip")
        val summary = buildString {
            appendLine("MoQPublisher diagnostics v1")
            appendLine("exported_at_utc=$stamp")
            appendLine("device_timezone=${TimeZone.getDefault().id}")
            appendLine("app_version=${version()}")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("hardware=${Build.HARDWARE}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("device_uptime_ms=${SystemClock.elapsedRealtime()}")
            appendLine("process_pss_kb=${Debug.getPss()}")
            appendLine("max_java_heap_bytes=${Runtime.getRuntime().maxMemory()}")
            appendLine("logical_cores=${Runtime.getRuntime().availableProcessors()}")
            appendLine("camera_permission=${context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED}")
            appendLine("microphone_permission=${context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}")
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            appendLine("battery_temperature_tenths_c=${battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)}")
            appendLine("network=${networkDescription()}")
            runCatching { performance() }.getOrDefault(emptyMap()).forEach { (key, value) -> appendLine("$key=${value ?: "--"}") }
            appendLine()
            appendLine("Current application state/settings (credentials omitted):")
            snapshot.forEach { (key, value) -> appendLine("$key=${value ?: "--"}") }
            appendLine()
            appendLine("AVC/HEVC encoder capabilities:")
            appendLine(encoderDescription())
        }
        recorder.export(destination, summary, processLog())
        // Bound cache usage while keeping recent exports available to share targets.
        directory.listFiles()?.filter { it.name.endsWith(".zip") && it != destination }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", destination)
    }

    internal fun networkDescription(): String = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching "unavailable"
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return@runCatching "offline"
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "vpn"
        ).filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }
        "$transports validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "uplink_kbps=${caps.linkUpstreamBandwidthKbps} downlink_kbps=${caps.linkDownstreamBandwidthKbps}"
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }

    internal fun networkType(): BroadcastNetwork = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching BroadcastNetwork.UNKNOWN
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return@runCatching BroadcastNetwork.OFFLINE
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> BroadcastNetwork.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> BroadcastNetwork.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> BroadcastNetwork.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> BroadcastNetwork.ETHERNET
            else -> BroadcastNetwork.UNKNOWN
        }
    }.getOrDefault(BroadcastNetwork.UNKNOWN)

    @Suppress("DEPRECATION")
    private fun version(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun encoderDescription(): String = runCatching {
        buildString {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }) {
                for (mime in info.supportedTypes.filter {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) || it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true)
                }) {
                    append("${info.name} mime=$mime")
                    if (Build.VERSION.SDK_INT >= 29) append(" hardware=${info.isHardwareAccelerated} software=${info.isSoftwareOnly}")
                    runCatching {
                        val caps = info.getCapabilitiesForType(mime)
                        append(" colors=${caps.colorFormats.joinToString()}")
                        append(" profiles=${caps.profileLevels.joinToString { "${it.profile}:${it.level}" }}")
                        caps.videoCapabilities?.let { video ->
                            append(" widths=${video.supportedWidths} heights=${video.supportedHeights}")
                            append(" fps=${video.supportedFrameRates} bitrate=${video.bitrateRange}")
                        }
                    }.onFailure { append(" capabilities_unavailable=${it.javaClass.simpleName}") }
                    appendLine()
                }
            }
        }
    }.getOrElse { "Encoder enumeration failed: ${it.javaClass.simpleName}: ${it.message}" }

    private fun processLog(): String {
        if (Build.VERSION.SDK_INT < 26) return "Process logcat snapshot unavailable on this Android version; see events.log.\n"
        val scratch = File.createTempFile("moq-process-log-", ".txt", context.cacheDir)
        var process: java.lang.Process? = null
        return try {
            process = ProcessBuilder(
                "/system/bin/logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", "500"
            ).redirectErrorStream(true).redirectOutput(scratch).start()
            val completed = process.waitFor(2, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                "Logcat snapshot timed out; see events.log.\n"
            } else {
                scratch.inputStream().use { input ->
                    input.channel.position((scratch.length() - 128 * 1024).coerceAtLeast(0))
                    input.readBytes().toString(Charsets.UTF_8).ifBlank { "No logcat entries visible to this process.\n" }
                }
            }
        } catch (e: Exception) {
            "Logcat snapshot unavailable: ${e.javaClass.simpleName}: ${e.message}\n"
        } finally {
            process?.destroy()
            scratch.delete()
        }
    }
}
