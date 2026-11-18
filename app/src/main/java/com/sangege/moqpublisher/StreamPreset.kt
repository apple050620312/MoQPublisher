package com.sangege.moqpublisher

import android.content.SharedPreferences
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.core.content.edit
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

data class StreamPreset(
    val id: String,
    val name: String,
    val videoCodec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateKbps: String,
    val adaptiveBitrate: Boolean,
    val enableAudio: Boolean,
    val audioChannel: AudioChannel,
    val audioQuality: AudioQuality,
    val builtIn: Boolean = false
)

internal object StreamPresetPolicy {
    const val CUSTOM_ID = "custom"
    private val supportedFps = setOf(15, 24, 30, 60)

    fun builtIns(): List<StreamPreset> = listOf(
        StreamPreset("data_saver", "Data Saver", "H264", 854, 480, 30, "1000", true,
            enableAudio = true, audioChannel = AudioChannel.MONO, audioQuality = AudioQuality.ECONOMY, builtIn = true),
        StreamPreset("mobile_streaming", "Mobile Streaming", "H264", 1280, 720, 30, "2500", true,
            enableAudio = true, audioChannel = AudioChannel.MONO, audioQuality = AudioQuality.STANDARD, builtIn = true),
        StreamPreset("high_quality", "High Quality", "H264", 1920, 1080, 30, "5000", true,
            enableAudio = true, audioChannel = AudioChannel.STEREO, audioQuality = AudioQuality.HIGH, builtIn = true),
        StreamPreset("high_fps", "High FPS", "H264", 1920, 1080, 60, "8000", true,
            enableAudio = true, audioChannel = AudioChannel.STEREO, audioQuality = AudioQuality.HIGH, builtIn = true)
    )

    fun validate(preset: StreamPreset): String? {
        if (preset.name.trim().isEmpty() || preset.name.trim().length > 64) return "name"
        if (preset.videoCodec !in setOf("H264", "H265")) return "codec"
        if (preset.width < 2 || preset.height < 2 || preset.width % 2 != 0 || preset.height % 2 != 0) return "resolution"
        if (preset.fps !in supportedFps) return "fps"
        if (VideoBitrateSettings.parseKbps(preset.videoBitrateKbps) == null) return "bitrate"
        return null
    }

    fun copyWithId(preset: StreamPreset, id: String, name: String): StreamPreset = preset.copy(
        id = id,
        name = name.trim().take(64),
        builtIn = false
    )
}

internal class StreamPresetRepository(private val prefs: SharedPreferences) {
    private companion object {
        const val KEY_PRESETS = "stream_presets_v1"
    }

    fun loadCustom(): List<StreamPreset> = runCatching {
        val encoded = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        buildList {
            for (line in encoded.lineSequence()) {
                val preset = parse(line) ?: continue
                if (!preset.builtIn && StreamPresetPolicy.validate(preset) == null) add(preset)
            }
        }
    }.getOrDefault(emptyList())

    fun saveCustom(current: StreamPreset, existingId: String? = null): StreamPreset {
        val id = existingId?.takeIf { it.isNotBlank() && it != StreamPresetPolicy.CUSTOM_ID }
            ?: UUID.randomUUID().toString()
        val preset = StreamPresetPolicy.copyWithId(current, id, current.name)
        require(StreamPresetPolicy.validate(preset) == null) { "Invalid stream preset" }
        val custom = loadCustom().filterNot { it.id == id } + preset
        persist(custom)
        return preset
    }

    fun delete(id: String): Boolean {
        val custom = loadCustom()
        if (custom.none { it.id == id }) return false
        persist(custom.filterNot { it.id == id })
        return true
    }

    fun deleteAll() {
        prefs.edit { remove(KEY_PRESETS) }
    }

    private fun persist(presets: List<StreamPreset>) {
        prefs.edit { putString(KEY_PRESETS, presets.joinToString("\n", transform = ::encode)) }
    }

    private fun encode(preset: StreamPreset): String = listOf(
        preset.id, preset.name, preset.videoCodec, preset.width.toString(), preset.height.toString(),
        preset.fps.toString(), preset.videoBitrateKbps, preset.adaptiveBitrate.toString(),
        preset.enableAudio.toString(), preset.audioChannel.name, preset.audioQuality.name
    ).joinToString("\t", transform = ::encodeText)

    private fun parse(line: String): StreamPreset? = runCatching {
        val fields = line.split('\t')
        if (fields.size != 11) return null
        StreamPreset(
            id = decodeText(fields[0]),
            name = decodeText(fields[1]),
            videoCodec = decodeText(fields[2]).uppercase(),
            width = fields[3].toInt(),
            height = fields[4].toInt(),
            fps = fields[5].toInt(),
            videoBitrateKbps = decodeText(fields[6]),
            adaptiveBitrate = fields[7].toBooleanStrict(),
            enableAudio = fields[8].toBooleanStrict(),
            audioChannel = AudioChannel.fromPreference(decodeText(fields[9])),
            audioQuality = AudioQuality.fromPreference(decodeText(fields[10]))
        )
    }.getOrNull()

    private fun encodeText(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun decodeText(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}

internal class DeviceCapabilityRepository {
    fun supportsHardwareSurfaceEncoder(codec: String): Boolean = runCatching {
        val mime = when (codec.uppercase()) {
            "H264" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "H265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> return@runCatching false
        }
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                isHardware(info) && MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in
                info.getCapabilitiesForType(mime).colorFormats
        }
    }.getOrDefault(false)

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") &&
            "ffmpeg" !in name && "sw.encoder" !in name && ".sw." !in name
    }
}
