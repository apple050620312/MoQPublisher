package com.sangege.moqpublisher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

enum class AppUpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, UP_TO_DATE, FAILED }

data class AppUpdateState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val currentVersion: String = "unknown",
    val latestVersion: String? = null,
    val progressPercent: Int? = null,
    val downloadedBytes: Long = 0,
    val downloadSize: Long = 0,
    val releaseUrl: String? = null,
    val downloadedPath: String? = null,
    val lastError: String? = null
) {
    val ready: Boolean get() = phase == AppUpdatePhase.READY && downloadedPath != null
}

private data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?
)

private data class AppRelease(
    val version: String,
    val pageUrl: String?,
    val apk: ReleaseAsset,
    val checksum: ReleaseAsset?
)

/** Checks and downloads signed release APKs published by this repository's GitHub Releases. */
class AppUpdateManager(context: Context, private val currentVersion: String = packageVersion(context)) {
    private companion object {
        const val TAG = "MoqAppUpdater"
        const val MAX_METADATA_BYTES = 1L * 1024 * 1024
        const val MAX_APK_BYTES = 160L * 1024 * 1024
        const val MAX_CHECKSUM_BYTES = 8L * 1024
        const val USER_AGENT = "MoQPublisher-AppUpdater"
        val API_HOST = "api.github.com"
        val DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )
    }

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateDirectory = File(this.context.cacheDir, "app-update")
    private val mutableState = MutableStateFlow(AppUpdateState(currentVersion = currentVersion))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var release: AppRelease? = null

    @Synchronized fun checkForUpdates() {
        if (job?.isActive == true) return
        val nextJob = scope.launch {
            mutableState.value = state.value.copy(
                phase = AppUpdatePhase.CHECKING,
                currentVersion = currentVersion,
                lastError = null
            )
            try {
                val latest = fetchLatestRelease()
                release = latest
                val base = AppUpdateState(
                    currentVersion = currentVersion,
                    latestVersion = latest.version,
                    releaseUrl = latest.pageUrl
                )
                if (AppUpdatePolicy.compare(latest.version, currentVersion) <= 0) {
                    mutableState.value = base.copy(phase = AppUpdatePhase.UP_TO_DATE)
                } else {
                    val cached = cachedFile(latest)
                    mutableState.value = if (cachedValid(cached, latest)) {
                        base.copy(phase = AppUpdatePhase.READY, downloadedPath = cached.absolutePath)
                    } else {
                        base.copy(phase = AppUpdatePhase.AVAILABLE)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "GitHub app update check failed", error)
                mutableState.value = state.value.copy(
                    phase = AppUpdatePhase.FAILED,
                    currentVersion = currentVersion,
                    lastError = error.javaClass.simpleName,
                    progressPercent = null
                )
            }
        }
        job = nextJob
    }

    @Synchronized fun downloadUpdate() {
        val candidate = release ?: run {
            checkForUpdates()
            return
        }
        if (job?.isActive == true || AppUpdatePolicy.compare(candidate.version, currentVersion) <= 0) return
        val nextJob = scope.launch {
            mutableState.value = state.value.copy(
                phase = AppUpdatePhase.DOWNLOADING,
                currentVersion = currentVersion,
                latestVersion = candidate.version,
                progressPercent = 0,
                downloadedBytes = 0,
                downloadSize = candidate.apk.size,
                lastError = null
            )
            try {
                val expected = candidate.apk.sha256 ?: candidate.checksum?.let { downloadChecksum(it) }
                check(expected != null) { "GitHub release has no SHA-256 checksum" }
                val target = cachedFile(candidate)
                val partial = File(target.parentFile, "${target.name}.part")
                downloadAsset(candidate.apk, partial)
                CoreLibraryFiles.verifySha256(partial, expected)
                check(partial.renameTo(target)) { "Cannot save downloaded APK" }
                mutableState.value = state.value.copy(
                    phase = AppUpdatePhase.READY,
                    currentVersion = currentVersion,
                    latestVersion = candidate.version,
                    progressPercent = 100,
                    downloadedPath = target.absolutePath,
                    lastError = null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "GitHub app update download failed", error)
                mutableState.value = state.value.copy(
                    phase = AppUpdatePhase.FAILED,
                    currentVersion = currentVersion,
                    progressPercent = null,
                    lastError = error.javaClass.simpleName
                )
            }
        }
        job = nextJob
    }

    fun downloadedFile(): File? = state.value.downloadedPath?.let(::File)?.takeIf { it.isFile }

    /** Run on IO: recheck the bytes as well as the Android archive before installation. */
    suspend fun installStatus(): AppUpdateInstallStatus {
        val candidate = release ?: return AppUpdateInstallStatus.MISSING
        val file = downloadedFile() ?: return AppUpdateInstallStatus.MISSING
        if (file.name != candidate.apk.name) return AppUpdateInstallStatus.MISSING
        val expected = candidate.apk.sha256 ?: candidate.checksum?.let { downloadChecksum(it) }
        if (expected == null || file.length() != candidate.apk.size ||
            runCatching { CoreLibraryFiles.verifySha256(file, expected) }.isFailure) {
            file.delete()
            mutableState.value = state.value.copy(phase = AppUpdatePhase.AVAILABLE, downloadedPath = null)
            return AppUpdateInstallStatus.INVALID
        }
        return AppUpdateInstaller.inspect(context, file, candidate.version)
    }

    private fun fetchLatestRelease(): AppRelease {
        val connection = open(AppUpdatePolicy.API_URL, downloads = false)
        return try {
            val json = connection.inputStream.use { input ->
                CoreLibraryFiles.readBounded(input, MAX_METADATA_BYTES.toInt()).toString(StandardCharsets.UTF_8)
            }
            parseRelease(JSONObject(json))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): AppRelease {
        check(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "GitHub release is not stable" }
        val tag = json.getString("tag_name")
        val version = AppUpdatePolicy.parseVersionTag(tag) ?: error("Invalid GitHub release tag")
        val apkName = AppUpdatePolicy.apkName(version)
        val assets = buildList {
            val array = json.optJSONArray("assets") ?: error("GitHub release has no assets")
            for (index in 0 until array.length()) {
                val asset = array.getJSONObject(index)
                add(
                    ReleaseAsset(
                        name = asset.getString("name"),
                        url = asset.getString("browser_download_url"),
                        size = asset.getLong("size"),
                        sha256 = AppUpdatePolicy.normalizeDigest(asset.opt("digest")?.toString())
                    )
                )
            }
        }
        val apk = assets.firstOrNull { it.name == apkName } ?: error("GitHub release APK is missing")
        check(apk.size in 1..MAX_APK_BYTES) { "Invalid GitHub release APK size" }
        check(isReleaseDownloadUrl(apk.url, version, apkName)) { "Untrusted GitHub release APK URL" }
        val checksum = assets.firstOrNull { it.name == "$apkName.sha256" || it.name == "$apkName.sha256.txt" }
        checksum?.let {
            check(it.size in 1..MAX_CHECKSUM_BYTES) { "Invalid GitHub release checksum size" }
            check(isReleaseDownloadUrl(it.url, version, it.name)) { "Untrusted GitHub checksum URL" }
        }
        return AppRelease(version, json.opt("html_url")?.toString(), apk, checksum)
    }

    private fun cachedFile(release: AppRelease): File {
        updateDirectory.mkdirs()
        return File(updateDirectory, release.apk.name)
    }

    private fun cachedValid(file: File, release: AppRelease): Boolean {
        val expected = release.apk.sha256 ?: return false
        return file.isFile && file.length() == release.apk.size &&
            runCatching { CoreLibraryFiles.verifySha256(file, expected) }.isSuccess
    }

    private suspend fun downloadChecksum(asset: ReleaseAsset): String {
        val connection = open(asset.url, downloads = true)
        return try {
            val text = connection.inputStream.use { input ->
                CoreLibraryFiles.readBounded(input, MAX_CHECKSUM_BYTES.toInt()).toString(StandardCharsets.UTF_8)
            }
            AppUpdatePolicy.checksumFromSidecar(text) ?: error("Invalid GitHub release checksum")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadAsset(asset: ReleaseAsset, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val connection = open(asset.url, downloads = true)
        try {
            connection.inputStream.buffered().use { input ->
                FileOutputStream(target).use { output ->
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= asset.size) { "GitHub release APK is larger than declared" }
                        output.write(buffer, 0, count)
                        mutableState.value = state.value.copy(
                            phase = AppUpdatePhase.DOWNLOADING,
                            progressPercent = (total * 100 / asset.size).toInt().coerceIn(0, 99),
                            downloadedBytes = total,
                            downloadSize = asset.size
                        )
                    }
                    output.fd.sync()
                    check(total == asset.size) { "GitHub release APK size mismatch" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, downloads: Boolean): HttpURLConnection {
        var current = url
        repeat(5) {
            val parsed = URL(current)
            check(parsed.protocol == "https") { "GitHub updater requires HTTPS" }
            check(if (downloads) parsed.host in DOWNLOAD_HOSTS else parsed.host == API_HOST) {
                "Untrusted GitHub updater host"
            }
            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            when (val response = connection.responseCode) {
                200 -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    current = URL(parsed, checkNotNull(location)).toExternalForm()
                }
                else -> {
                    connection.disconnect()
                    error("GitHub updater HTTP $response")
                }
            }
        }
        error("Too many GitHub updater redirects")
    }

    private fun isReleaseDownloadUrl(url: String, version: String, name: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        val expectedPrefix = "/${AppUpdatePolicy.OWNER}/${AppUpdatePolicy.REPOSITORY}/releases/download/"
        return parsed.protocol == "https" && parsed.host == "github.com" &&
            parsed.path.startsWith(expectedPrefix) && parsed.path.endsWith("/$name") &&
            parsed.path.contains("/v$version/")
    }
}

private fun packageVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull()?.takeIf(AppUpdatePolicy::validVersion) ?: "0.0.0"
