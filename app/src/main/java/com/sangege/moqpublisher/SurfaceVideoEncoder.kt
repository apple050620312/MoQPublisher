package com.sangege.moqpublisher

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.moq.VideoCodec
import java.util.concurrent.atomic.AtomicInteger

/** Camera pixels stay on GPU/codec surfaces. Only compressed access units enter Kotlin/MoQ. */
internal class SurfaceVideoEncoder private constructor(
    private val codec: MediaCodec,
    val inputSurface: Surface,
    private val worker: HandlerThread,
    override val name: String,
    override val width: Int,
    override val height: Int,
    initialBitrate: Long,
    private val packetizer: AnnexB,
    private val diagnostics: AppDiagnostics,
    private val onEncoded: (AnnexB.AccessUnit, ByteArray, Long) -> Unit,
    private val onFailure: (Throwable) -> Unit
) : VideoEncoder {
    private val lock = Any()
    private val pending = AtomicInteger()
    @Volatile private var closed = false
    private var lastKeyRequestUs = Long.MIN_VALUE
    @Volatile private var lastProgressNs = System.nanoTime()
    override val pendingFrames: Int get() = pending.get()
    @Volatile override var bitrateBps = initialBitrate
        private set

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = synchronized(lock) {
            if (!closed) for (slot in 0..2) format.getByteBuffer("csd-$slot")?.let { buffer ->
                packetizer.configure(ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) })
            }
        }
        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            if (!closed) onFailure(error)
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                synchronized(lock) {
                    if (closed) return
                    val started = System.nanoTime()
                    val bytes = try {
                        if (info.size <= 0) null else checkNotNull(codec.getOutputBuffer(index)).duplicate().run {
                            position(info.offset); limit(info.offset + info.size)
                            ByteArray(remaining()).also { get(it) }
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (bytes == null) return
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        packetizer.configure(bytes)
                        return
                    }
                    pending.updateAndGet { (it - 1).coerceAtLeast(0) }
                    lastProgressNs = System.nanoTime()
                    val unit = packetizer.accessUnit(bytes) ?: return
                    diagnostics.metrics.count(StreamMetrics.Counter.ENCODED)
                    if (unit.keyframe) diagnostics.metrics.count(StreamMetrics.Counter.KEYFRAME)
                    onEncoded(unit, packetizer.configuration(), info.presentationTimeUs)
                    diagnostics.metrics.timing(StreamMetrics.Stage.ENCODER_DRAIN, System.nanoTime() - started)
                }
            } catch (error: Throwable) { if (!closed) onFailure(error) }
        }
    }

    private fun start() { codec.setCallback(callback, Handler(worker.looper)); codec.start() }
    fun submitted() {
        if (pending.getAndIncrement() == 0) lastProgressNs = System.nanoTime()
    }

    fun checkProgress() {
        check(pending.get() == 0 || System.nanoTime() - lastProgressNs < 3_000_000_000L) { "Surface encoder stopped producing frames" }
    }

    override fun setBitrate(bitrateBps: Long) = synchronized(lock) {
        check(!closed)
        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps.toInt()) })
        this.bitrateBps = bitrateBps
    }

    override fun requestKeyframe(timestampUs: Long) = synchronized(lock) {
        if (!closed && (lastKeyRequestUs == Long.MIN_VALUE || timestampUs - lastKeyRequestUs >= 250_000)) {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            lastKeyRequestUs = timestampUs
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { codec.stop() }
            runCatching { codec.release() }
            inputSurface.release()
            pending.set(0)
        }
        worker.quitSafely()
    }

    companion object {
        fun open(
            width: Int, height: Int, fps: Int, bitrate: Long, type: VideoCodec,
            diagnostics: AppDiagnostics,
            onFailure: (Throwable) -> Unit,
            onEncoded: (AnnexB.AccessUnit, ByteArray, Long) -> Unit
        ): SurfaceVideoEncoder {
            val hevc = type == VideoCodec.H265
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .sortedByDescending { it.isHardwareVideoEncoder() }
            var lastFailure: Exception? = null
            for (info in candidates) {
                // A vendor may advertise an optional setting and still reject it at some modes.
                val caps = runCatching { info.getCapabilitiesForType(mime) }
                    .onFailure { diagnostics.error("surface_encoder_capabilities_unavailable", it) }
                    .getOrNull()
                for (lowLatency in listOf(true, false)) {
                    var codec: MediaCodec? = null
                    var surface: Surface? = null
                    var worker: HandlerThread? = null
                    try {
                        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                            setInteger(MediaFormat.KEY_BIT_RATE, bitrate.toInt())
                            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                            if (lowLatency) setLowLatencyOptions(hevc, caps)
                        }
                        codec = MediaCodec.createByCodecName(info.name)
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        surface = codec.createInputSurface()
                        worker = HandlerThread("MoqCodecOutput", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
                        val encoder = SurfaceVideoEncoder(
                            codec, surface, worker, info.name, width, height, bitrate, AnnexB(hevc), diagnostics, onEncoded, onFailure
                        )
                        encoder.start()
                        diagnostics.event("encoder_open", mapOf(
                            "name" to info.name, "input" to "surface", "format" to format,
                            "hardware_accelerated" to info.isHardwareVideoEncoder(), "low_latency_keys" to lowLatency
                        ))
                        return encoder
                    } catch (error: Exception) {
                        runCatching { codec?.release() }
                        runCatching { surface?.release() }
                        worker?.quitSafely()
                        diagnostics.event("surface_encoder_candidate_failed", mapOf(
                            "name" to info.name, "codec" to type, "width" to width, "height" to height,
                            "fps" to fps, "bitrate_bps" to bitrate, "low_latency_keys" to lowLatency, "error" to error.toString()
                        ))
                        lastFailure = error
                    }
                }
            }
            throw UiException(
                UiMessage(R.string.error_encoder_unsupported, listOf(if (hevc) "H.265" else "H.264", width, height, fps)),
                lastFailure
            )
        }

        private fun MediaFormat.setLowLatencyOptions(hevc: Boolean, caps: MediaCodecInfo.CodecCapabilities?) {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 26) setInteger(MediaFormat.KEY_LATENCY, 0)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            val profile = if (hevc) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            if (caps?.profileLevels?.any { it.profile == profile } == true) setInteger(MediaFormat.KEY_PROFILE, profile)
            val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            if (caps?.encoderCapabilities?.isBitrateModeSupported(cbr) == true) setInteger(MediaFormat.KEY_BITRATE_MODE, cbr)
        }
    }
}
