package com.sangege.moqpublisher

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.camera.core.ImageProxy
import dev.moq.VideoCodec
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Android hardware encoding; only compressed access units cross the MoQ FFI boundary. */
internal class HardwareVideoEncoder private constructor(
    private val encoder: MediaCodec,
    override val name: String,
    override val width: Int,
    override val height: Int,
    initialBitrateBps: Long,
    private val colorFormat: Int,
    hevc: Boolean,
    private val diagnostics: AppDiagnostics,
    private val onEncoded: (AnnexB.AccessUnit, ByteArray, Long) -> Unit
) : VideoEncoder {
    private val packetizer = AnnexB(hevc)
    private val info = MediaCodec.BufferInfo()
    private val pending = ArrayDeque<Long>()
    private val planeScratch = Array(3) { YuvPlaneCopy.Scratch() }
    private var lastKeyframeUs: Long? = null
    private var lastKeyRequestUs: Long? = null
    private var lastInputUs = -1L
    private var lastOutputWallNs = System.nanoTime()
    private var lastStallRecoveryWallNs = 0L
    private var closed = false

    override val pendingFrames: Int get() = pending.size
    override var bitrateBps: Long = initialBitrateBps
        private set

    // All methods run under the streamer's video lock. Never sleep waiting for
    // an input slot: CameraX retains only the newest camera frame instead.
    fun write(image: ImageProxy, timestampUs: Long) {
        check(!closed)
        require(image.width == width && image.height == height && image.planes.size == 3)
        drain()
        recoverFromOutputStallIfNeeded(timestampUs)
        if (pending.size >= 6) {
            diagnostics.metrics.count(StreamMetrics.Counter.BUSY_DROP)
            return
        }

        val inputStartedNs = System.nanoTime()
        val inputIndex = encoder.dequeueInputBuffer(0)
        if (inputIndex < 0) {
            diagnostics.metrics.timing(StreamMetrics.Stage.ENCODER_INPUT, System.nanoTime() - inputStartedNs)
            diagnostics.metrics.count(StreamMetrics.Counter.BUSY_DROP)
            return
        }

        val copyStartedNs = System.nanoTime()
        val inputImage = encoder.getInputImage(inputIndex)
        if (inputImage != null) {
            for (plane in 0..2) {
                val source = image.planes[plane]
                val target = inputImage.planes[plane]
                val scale = if (plane == 0) 1 else 2
                YuvPlaneCopy.copy(
                    source.buffer, source.rowStride, source.pixelStride,
                    target.buffer, target.rowStride, target.pixelStride,
                    width / scale, height / scale, planeScratch[plane]
                )
            }
        } else {
            // Older encoders may only advertise the concrete I420/NV12 layout.
            val buffer = checkNotNull(encoder.getInputBuffer(inputIndex))
            buffer.clear()
            copyToPackedBuffer(image, buffer)
        }
        val copyNs = System.nanoTime() - copyStartedNs
        diagnostics.metrics.timing(StreamMetrics.Stage.PIXEL_COPY, copyNs)

        val ptsUs = maxOf(timestampUs, lastInputUs + 1)
        if (lastKeyframeUs?.let { ptsUs - it >= 1_000_000L } == true) requestKeyframe(ptsUs)
        encoder.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0)
        diagnostics.metrics.timing(StreamMetrics.Stage.ENCODER_INPUT, System.nanoTime() - inputStartedNs - copyNs)
        pending.addLast(ptsUs)
        diagnostics.metrics.count(StreamMetrics.Counter.SUBMITTED)
        lastInputUs = ptsUs
        drain()
    }

    override fun requestKeyframe(timestampUs: Long) {
        if (lastKeyRequestUs?.let { timestampUs - it < 250_000L } == true) return
        encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        lastKeyRequestUs = timestampUs
    }

    override fun setBitrate(bitrateBps: Long) {
        encoder.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        })
        this.bitrateBps = bitrateBps
    }

    private fun copyToPackedBuffer(image: ImageProxy, buffer: ByteBuffer) {
        val planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val semiplanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        check(planar || semiplanar) { "Hardware encoder did not expose a writable YUV input image" }
        val lumaSize = width * height
        for (plane in 0..2) {
            val scale = if (plane == 0) 1 else 2
            val pixelStride = if (plane == 0 || planar) 1 else 2
            val rowStride = width / scale * pixelStride
            val offset = when {
                plane == 0 -> 0
                planar -> lumaSize + (plane - 1) * lumaSize / 4
                else -> lumaSize + plane - 1
            }
            val target = buffer.duplicate().apply { position(offset) }
            val source = image.planes[plane]
            YuvPlaneCopy.copy(
                source.buffer, source.rowStride, source.pixelStride,
                target, rowStride, pixelStride, width / scale, height / scale, planeScratch[plane]
            )
        }
    }

    private fun drain() {
        val startedNs = System.nanoTime()
        try {
            drainOutput()
        } finally {
            diagnostics.metrics.timing(StreamMetrics.Stage.ENCODER_DRAIN, System.nanoTime() - startedNs)
        }
    }

    private fun drainOutput() {
        while (true) {
            when (val index = encoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = encoder.outputFormat
                    diagnostics.event("encoder_output_format", mapOf("format" to format))
                    for (slot in 0..2) {
                        format.getByteBuffer("csd-$slot")?.let { packetizer.configure(it.bytes()) }
                    }
                }
                else -> if (index >= 0) {
                    val flags = info.flags
                    val timestampUs = info.presentationTimeUs
                    val bytes = try {
                        if (info.size > 0) {
                            checkNotNull(encoder.getOutputBuffer(index)).duplicate().apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                            }.bytes()
                        } else null
                    } finally {
                        encoder.releaseOutputBuffer(index, false)
                    }
                    if (bytes == null) continue
                    lastOutputWallNs = System.nanoTime()
                    if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        packetizer.configure(bytes)
                        continue
                    }
                    while (pending.peekFirst()?.let { it <= timestampUs } == true) pending.removeFirst()
                    val accessUnit = packetizer.accessUnit(bytes) ?: continue
                    diagnostics.metrics.count(StreamMetrics.Counter.ENCODED)
                    if (accessUnit.keyframe) {
                        lastKeyframeUs = timestampUs
                        diagnostics.metrics.count(StreamMetrics.Counter.KEYFRAME)
                    }
                    onEncoded(accessUnit, packetizer.configuration(), timestampUs)
                }
            }
        }
    }

    /**
     * A vendor codec can occasionally stop returning output while the camera
     * keeps producing frames. Flush once before treating it as a fatal codec
     * failure; this lets the next keyframe restore the stream without turning a
     * short hardware hiccup into a reconnect and a long viewer spinner.
     */
    private fun recoverFromOutputStallIfNeeded(timestampUs: Long) {
        val oldestPending = pending.peekFirst() ?: return
        val now = System.nanoTime()
        val pendingAgeUs = timestampUs - oldestPending
        val wallStallNs = now - lastOutputWallNs
        if (pendingAgeUs < 3_000_000L && wallStallNs < 3_000_000_000L) return

        if (now - lastStallRecoveryWallNs >= 3_000_000_000L) {
            lastStallRecoveryWallNs = now
            diagnostics.event("encoder_output_stall_recovery", mapOf(
                "pending_frames" to pending.size,
                "pending_age_ms" to pendingAgeUs / 1_000L,
                "wall_stall_ms" to wallStallNs / 1_000_000L
            ))
            runCatching {
                encoder.flush()
                pending.clear()
                lastInputUs = timestampUs - 1L
                lastOutputWallNs = now
                requestKeyframe(timestampUs)
            }.onFailure {
                diagnostics.error("encoder_output_stall_recovery_failed", it)
            }
            return
        }

        // A second stall after the recovery is a real encoder failure. Let the
        // streamer surface the error and recreate the session instead of
        // allowing an unbounded pending queue.
        if (pendingAgeUs >= 8_000_000L || wallStallNs >= 8_000_000_000L) {
            throw IllegalStateException("Hardware video encoder stopped producing frames")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        diagnostics.event("encoder_close", mapOf("pending_frames" to pending.size))
        // This is a live source: release queued pictures on stop instead of
        // blocking microphone/teardown to drain a stale encoder tail.
        try {
            encoder.stop()
        } finally {
            encoder.release()
        }
    }

    companion object {
        fun open(
            width: Int,
            height: Int,
            framerate: Int,
            bitrateBps: Long,
            codec: VideoCodec,
            diagnostics: AppDiagnostics,
            onEncoded: (AnnexB.AccessUnit, ByteArray, Long) -> Unit
        ): HardwareVideoEncoder {
            val hevc = codec == VideoCodec.H265
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val failures = mutableListOf<String>()
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } && isHardware(it)
            }
            for (candidate in candidates) {
                var encoder: MediaCodec? = null
                try {
                    val capabilities = candidate.getCapabilitiesForType(mime)
                    val color = listOf(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    ).firstOrNull { it in capabilities.colorFormats } ?: continue
                    val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        setInteger(MediaFormat.KEY_FRAME_RATE, framerate)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        if (Build.VERSION.SDK_INT >= 26) setInteger(MediaFormat.KEY_LATENCY, 0)
                        if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        val profile = if (hevc) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                        if (capabilities.profileLevels.any { it.profile == profile }) setInteger(MediaFormat.KEY_PROFILE, profile)
                        if (capabilities.encoderCapabilities
                                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                        }
                    }
                    encoder = MediaCodec.createByCodecName(candidate.name)
                    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder.start()
                    Log.i("HardwareVideoEncoder", "Using ${candidate.name}: $codec ${width}x$height@$framerate")
                    diagnostics.event("encoder_open", mapOf("name" to candidate.name, "codec" to codec, "format" to format))
                    return HardwareVideoEncoder(encoder, candidate.name, width, height, bitrateBps, color, hevc, diagnostics, onEncoded)
                } catch (e: Exception) {
                    runCatching { encoder?.release() }
                    diagnostics.event("encoder_candidate_failed", mapOf("name" to candidate.name, "error" to e.toString()))
                    failures += "${candidate.name}: ${e.message}"
                }
            }
            throw UiException(
                UiMessage(R.string.error_encoder_unsupported, listOf(if (hevc) "H.265" else "H.264", width, height, framerate)),
                IllegalStateException(failures.joinToString())
            )
        }

        private fun isHardware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
            val name = info.name.lowercase()
            return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") &&
                "ffmpeg" !in name && "sw.encoder" !in name && ".sw." !in name
        }

        private fun ByteBuffer.bytes(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }
    }
}
