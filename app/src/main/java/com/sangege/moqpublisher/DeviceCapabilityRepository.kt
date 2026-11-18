package com.sangege.moqpublisher

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range

data class VideoEncoderCapability(
    val codec: String,
    val encoderNames: List<String> = emptyList(),
    val surfaceInput: Boolean = false,
    val widthRange: Range<Int>? = null,
    val heightRange: Range<Int>? = null,
    val frameRateRange: Range<Int>? = null,
    val bitrateRange: Range<Int>? = null,
    val cbrSupported: Boolean = false
) {
    val available: Boolean get() = encoderNames.isNotEmpty() && surfaceInput

    fun toDiagnosticMap(): Map<String, Any?> = mapOf(
        "codec" to codec,
        "encoders" to encoderNames,
        "surface_input" to surfaceInput,
        "widths" to widthRange?.toString(),
        "heights" to heightRange?.toString(),
        "fps" to frameRateRange?.toString(),
        "bitrate" to bitrateRange?.toString(),
        "cbr" to cbrSupported
    )
}

internal class DeviceCapabilityRepository {
    fun inspectAll(): List<VideoEncoderCapability> = listOf(inspect("H264"), inspect("H265"))

    fun inspect(codec: String): VideoEncoderCapability = runCatching {
        val normalized = codec.uppercase()
        val mime = when (normalized) {
            "H264" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "H265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> return@runCatching VideoEncoderCapability(normalized)
        }
        var surface = false
        var widths: Range<Int>? = null
        var heights: Range<Int>? = null
        var frameRates: Range<Int>? = null
        var bitrates: Range<Int>? = null
        var cbr = false
        val names = mutableListOf<String>()
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(mime, ignoreCase = true) } ||
                !info.isHardwareVideoEncoder()) return@forEach
            runCatching {
                val caps = info.getCapabilitiesForType(mime)
                names += info.name
                surface = surface || MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats
                caps.videoCapabilities?.let { video ->
                    widths = merge(widths, video.supportedWidths)
                    heights = merge(heights, video.supportedHeights)
                    frameRates = merge(frameRates, video.supportedFrameRates)
                    bitrates = merge(bitrates, video.bitrateRange)
                }
                cbr = cbr || caps.encoderCapabilities?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) == true
            }
        }
        VideoEncoderCapability(normalized, names.distinct(), surface, widths, heights, frameRates, bitrates, cbr)
    }.getOrDefault(VideoEncoderCapability(codec.uppercase()))

    private fun merge(current: Range<Int>?, next: Range<Int>): Range<Int> = if (current == null) next else
        Range(minOf(current.lower, next.lower), maxOf(current.upper, next.upper))
}
