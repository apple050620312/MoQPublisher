package com.sangege.moqpublisher

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import android.os.Build
import android.os.Process
import com.sun.jna.NativeLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.zip.ZipFile

enum class CorePhase { CHECKING, DOWNLOADING, READY, FAILED, DEFERRED }
enum class CoreNotice { NONE, OFFLINE, INCOMPATIBLE, UPDATE_READY, DEFERRED }
data class CoreState(
    val phase: CorePhase = CorePhase.CHECKING,
    val activeVersion: String? = null,
    val pendingVersion: String? = null,
    val latestVersion: String? = null,
    val progressPercent: Int? = null,
    val downloadedBytes: Long = 0,
    val downloadSize: Long = 0,
    val notice: CoreNotice = CoreNotice.NONE,
    val lastError: String? = null,
    val abi: String? = null
) { val ready: Boolean get() = activeVersion != null }

/** Official Maven releases, persistent cache per process ABI, and checked activation on cold start. */
class MoqCoreManager(context: Context) {
    private val context = context.applicationContext
    private val architecture = checkNotNull(CoreArchitecture.select(Build.SUPPORTED_ABIS.toList(), Process.is64Bit()))
    private val root = File(this.context.noBackupFilesDir, "moq-core/${architecture.abi}")
    private val marker = AtomicFile(File(root, "selected.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(CoreState(abi = architecture.abi))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var streaming = false
    private var active: JSONObject? = null
    private val expected: Map<String, Int> by lazy {
        val properties = Properties().apply {
            this@MoqCoreManager.context.assets.open("moq-core-api.properties").use(::load)
        }
        properties.stringPropertyNames().associateWith { properties.getProperty(it).toInt() }
            .also { check(it.size >= 200) }
    }
    private val apiFingerprint by lazy { expected.toSortedMap().toString() }

    @Synchronized fun checkForUpdates() {
        if (job?.isActive == true) return
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            root.mkdirs()
            if (active == null) restoreInstalled()
            mutableState.value = mutableState.value.copy(phase = CorePhase.CHECKING, notice = CoreNotice.NONE, lastError = null)
            try {
                update()
            } catch (_: DeferredUpdate) {
                mutableState.value = mutableState.value.copy(phase = CorePhase.DEFERRED, notice = CoreNotice.DEFERRED)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w("MoqCore", "Official core update failed", error)
                mutableState.value = mutableState.value.copy(
                    phase = if (active != null) CorePhase.READY else CorePhase.FAILED,
                    notice = if (active != null) CoreNotice.OFFLINE else CoreNotice.NONE,
                    lastError = error.javaClass.simpleName
                )
            }
        }
        job = nextJob
        nextJob.invokeOnCompletion {
            // A broadcast may end between publishing DEFERRED and completing
            // this job. Resume even if its idle notification hit that window.
            if (!streaming && state.value.phase == CorePhase.DEFERRED) checkForUpdates()
        }
        nextJob.start()
    }

    fun setStreaming(value: Boolean) {
        streaming = value
        if (!value && state.value.phase == CorePhase.DEFERRED) checkForUpdates()
    }

    private fun restoreInstalled() {
        val selected = runCatching { marker.openRead().bufferedReader().use { JSONObject(it.readText()) } }.getOrNull()
        if (selected != null) {
            val candidates = listOfNotNull(selected, selected.optJSONObject("previous"))
            for (candidate in candidates) {
                val valid = runCatching {
                    val version = candidate.getString("version")
                    check(CoreUpdatePolicy.validVersion(version) && candidate.getString("api") == apiFingerprint)
                    val library = descriptorLibrary(candidate)
                    CoreLibraryFiles.verifySha256(library, candidate.getString("sha256"))
                    CoreLibraryFiles.validate(library, architecture)
                    verifyAbi(library)
                    activate(candidate)
                }
                if (valid.isSuccess) return
                Log.w("MoqCore", "Ignoring unusable cached core", valid.exceptionOrNull())
            }
        }
        restoreBundled()
    }

    private fun activate(descriptor: JSONObject) {
        // UniFFI reads this before initializing either native symbol table. Never
        // change it once this process can start a broadcast.
        check(active == null)
        val version = descriptor.getString("version")
        System.setProperty("uniffi.component.moq.libraryOverride", descriptorLibrary(descriptor).absolutePath)
        active = descriptor
        mutableState.value = CoreState(phase = CorePhase.READY, activeVersion = version, abi = architecture.abi)
    }

    /** Offline builds carry the official AAR core as an asset and install it privately. */
    private fun restoreBundled() {
        if (active != null) return
        val asset = "moq-core-offline/${architecture.abi}/libmoq_ffi.so"
        val expectedSha256 = runCatching {
            context.assets.open("$asset.sha256").bufferedReader().use { it.readText().trim() }
                .also { check(Regex("[0-9a-fA-F]{64}").matches(it)) }
        }.getOrNull() ?: return
        val target = File(root, "bundled/libmoq_ffi.so")
        val installed = runCatching {
            if (!target.exists()) throw IllegalStateException("bundled core is not installed")
            CoreLibraryFiles.verifySha256(target, expectedSha256)
            CoreLibraryFiles.validate(target, architecture)
            verifyAbi(target)
        }.isSuccess
        if (!installed) {
            val partial = File(target.parentFile, "libmoq_ffi.so.partial")
            val copied = runCatching {
                target.parentFile?.mkdirs()
                context.assets.open(asset).use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= CoreLibraryFiles.MAX_LIBRARY_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                        check(total > 0)
                    }
                }
                CoreLibraryFiles.verifySha256(partial, expectedSha256)
                CoreLibraryFiles.validate(partial, architecture)
                verifyAbi(partial)
                target.delete()
                check(partial.renameTo(target)) { "Cannot install bundled core" }
                check(target.setReadOnly())
            }.isSuccess
            partial.delete()
            if (!copied) return
        }
        val descriptor = JSONObject()
            .put("version", CoreUpdatePolicy.BASE_VERSION)
            .put("sha256", CoreLibraryFiles.sha256(target))
            .put("api", apiFingerprint)
            .put("path", target.absolutePath)
            .put("bundled", true)
        activate(descriptor)
    }

    private suspend fun update() {
        var metadataAvailable = true
        val versions = try {
            CoreUpdatePolicy.versions(fetchText("${CoreUpdatePolicy.REPOSITORY}/maven-metadata.xml"))
        } catch (error: Exception) {
            if (active != null) throw error
            // The known release remains installable even during a metadata outage.
            metadataAvailable = false
            listOf(CoreUpdatePolicy.BASE_VERSION)
        }
        check(versions.isNotEmpty()) { "No official stable core releases" }
        val latest = versions.first()
        mutableState.value = mutableState.value.copy(latestVersion = latest)
        val selected = runCatching { marker.openRead().bufferedReader().use { JSONObject(it.readText()) } }.getOrNull()
        val installedVersion = active?.getString("version")
        val candidates = (versions.take(12) + CoreUpdatePolicy.BASE_VERSION).distinct()
        for (version in candidates) {
            if (installedVersion != null && CoreUpdatePolicy.compare(version, installedVersion) <= 0) break
            if (selected?.optString("version") == version && version != installedVersion && cachedValid(selected)) {
                mutableState.value = mutableState.value.copy(phase = CorePhase.READY, pendingVersion = version, notice = CoreNotice.UPDATE_READY)
                return
            }
            deferDuringStream()
            val module = JSONObject(fetchText(CoreUpdatePolicy.artifactUrl(version, "moq-ffi-android-$version.module")))
            val artifacts = buildList {
                val variants = module.getJSONArray("variants")
                for (i in 0 until variants.length()) {
                    val files = variants.getJSONObject(i).optJSONArray("files") ?: continue
                    for (j in 0 until files.length()) add(files.getJSONObject(j))
                }
            }
            val sourceInfo = artifacts.first { it.getString("url").endsWith("-sources.jar") }
            val scratch = File(context.cacheDir, "moq-core-update").apply { mkdirs() }
            val sourceJar = File(scratch, "sources.jar")
            val archive = File(scratch, "core.aar")
            val extracted = File(scratch, "libmoq_ffi.so.partial")
            try {
                download(version, sourceInfo, sourceJar, reportProgress = false)
                val bindings = ZipFile(sourceJar).use { zip ->
                    val entry = zip.entries().asSequence().first { it.name.endsWith("/uniffi/moq/moq.kt") }
                    check(entry.size in 1..2_000_000)
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
                if (!CoreUpdatePolicy.compatible(expected, CoreUpdatePolicy.bindingChecksums(bindings))) continue
                val artifact = artifacts.first { it.getString("url").endsWith(".aar") }
                download(version, artifact, archive, reportProgress = true)
                CoreLibraryFiles.extract(archive, extracted, architecture)
                try { verifyAbi(extracted) }
                catch (error: LinkageError) {
                    Log.w("MoqCore", "Core cannot load on this Android version", error)
                    continue
                } catch (error: IllegalStateException) {
                    Log.w("MoqCore", "Core binary does not match bindings", error)
                    continue
                }
                val directory = library(version).parentFile!!
                check(directory.isDirectory || directory.mkdirs())
                val target = library(version)
                // Versions are immutable. An existing corrupt candidate is never
                // the active library, since installedVersion was handled above.
                if (target.exists()) check(target.delete())
                check(extracted.renameTo(target)) { "Cannot install core library" }
                check(target.setReadOnly())
                val descriptor = JSONObject().put("version", version).put("sha256", CoreLibraryFiles.sha256(target)).put("api", apiFingerprint)
                active?.let { descriptor.put("previous", JSONObject(it.toString()).apply { remove("previous") }) }
                saveSelected(descriptor)
                if (active == null) activate(descriptor)
                else mutableState.value = mutableState.value.copy(phase = CorePhase.READY, pendingVersion = version, notice = CoreNotice.UPDATE_READY)
                mutableState.value = mutableState.value.copy(latestVersion = if (metadataAvailable) latest else null,
                    progressPercent = null, notice = when {
                        mutableState.value.pendingVersion != null -> CoreNotice.UPDATE_READY
                        !metadataAvailable -> CoreNotice.OFFLINE
                        else -> CoreNotice.NONE
                    })
                pruneVersions(descriptor)
                return
            } finally {
                sourceJar.delete(); archive.delete(); extracted.delete()
            }
        }
        check(active != null) { "No compatible official core" }
        mutableState.value = mutableState.value.copy(phase = CorePhase.READY, progressPercent = null,
            notice = if (latest != installedVersion) CoreNotice.INCOMPATIBLE else CoreNotice.NONE)
    }

    private fun cachedValid(descriptor: JSONObject) = runCatching {
        val file = descriptorLibrary(descriptor)
        check(descriptor.getString("api") == apiFingerprint)
        CoreLibraryFiles.verifySha256(file, descriptor.getString("sha256"))
        CoreLibraryFiles.validate(file, architecture)
        verifyAbi(file)
    }.isSuccess

    private fun verifyAbi(file: File) {
        NativeLibrary.getInstance(file.absolutePath).use { native ->
            for ((symbol, checksum) in expected) {
                val result = native.getFunction(symbol).invokeInt(emptyArray())
                val actual = if (symbol.endsWith("contract_version")) result else result and 0xffff
                check(actual == checksum) { "Core ABI mismatch: $symbol" }
            }
        }
    }

    private fun library(version: String): File {
        require(CoreUpdatePolicy.validVersion(version))
        return File(root, "$version/libmoq_ffi.so")
    }

    private fun descriptorLibrary(descriptor: JSONObject): File {
        val file = descriptor.optString("path").takeIf { it.isNotBlank() }?.let(::File)
            ?: library(descriptor.getString("version"))
        val rootPath = root.canonicalFile.path + File.separator
        check(file.canonicalFile.path.startsWith(rootPath)) { "Core path escapes the private core directory" }
        return file
    }

    private fun saveSelected(descriptor: JSONObject) {
        val output = marker.startWrite()
        try { output.write(descriptor.toString().toByteArray(Charsets.UTF_8)); marker.finishWrite(output) }
        catch (error: Throwable) { marker.failWrite(output); throw error }
    }

    private fun pruneVersions(descriptor: JSONObject) {
        val keep = setOf(descriptor.getString("version"), descriptor.optJSONObject("previous")?.optString("version"))
        root.listFiles()?.filter { it.isDirectory && CoreUpdatePolicy.validVersion(it.name) && it.name !in keep }?.forEach {
            File(it, "libmoq_ffi.so").delete()
            it.delete()
        }
    }

    private fun connection(url: String): HttpURLConnection {
        check(url.startsWith("${CoreUpdatePolicy.REPOSITORY}/"))
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "MoQPublisher-CoreUpdater")
        }
    }

    private suspend fun fetchText(url: String): String {
        deferDuringStream()
        val connection = connection(url)
        try {
            check(connection.responseCode == 200) { "Official repository HTTP ${connection.responseCode}" }
            return connection.inputStream.use { input ->
                val data = CoreLibraryFiles.readBounded(input, 512 * 1024)
                data.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    private suspend fun download(version: String, artifact: JSONObject, target: File, reportProgress: Boolean) {
        deferDuringStream()
        val size = artifact.getLong("size")
        check(size in 1..CoreLibraryFiles.MAX_ARTIFACT_BYTES)
        val connection = connection(CoreUpdatePolicy.artifactUrl(version, artifact.getString("url")))
        try {
            check(connection.responseCode == 200)
            connection.inputStream.buffered().use { input ->
                FileOutputStream(target).use { output ->
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        deferDuringStream()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= size)
                        output.write(buffer, 0, count)
                        if (reportProgress) mutableState.value = mutableState.value.copy(phase = CorePhase.DOWNLOADING,
                            progressPercent = (total * 100 / size).toInt(), downloadedBytes = total, downloadSize = size)
                    }
                    check(total == size)
                    output.fd.sync()
                }
            }
            CoreLibraryFiles.verifySha256(target, artifact.getString("sha256"))
        } finally { connection.disconnect() }
    }

    private fun deferDuringStream() { if (streaming && active != null) throw DeferredUpdate() }
    private class DeferredUpdate : Exception()
}
