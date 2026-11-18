package com.sangege.moqpublisher

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.SystemClock
import android.os.StatFs
import dev.moq.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App-private MP4 recorder fed by the already encoded video and captured PCM.
 * It owns a separate worker, so a damaged disk or muxer cannot stop MoQ.
 */
internal class LocalRecordingManager(
    private val context: android.content.Context,
    private val diagnostics: AppDiagnostics
) : AutoCloseable {
    private sealed interface Event {
        data class Video(val frame: RecordingVideoFrame) : Event
        data class Audio(val pcm: ByteArray, val timestampUs: Long, val channels: Int) : Event
        data object Stop : Event
    }

    private val mutableState = MutableStateFlow(RecordingState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private val events = ArrayBlockingQueue<Event>(240)
    private var executor: ExecutorService? = null
    @Volatile private var accepting = false
    private var worker: RecordingWorker? = null

    fun start(preferences: RecordingPreferences, codec: VideoCodec, audioEnabled: Boolean) {
        synchronized(lock) {
            if (accepting) return
            if (preferences.mode == RecordingMode.STREAM_ONLY) {
                mutableState.value = RecordingState(mode = preferences.mode)
                return
            }
            accepting = true
            events.clear()
            mutableState.value = RecordingState(mode = preferences.mode)
            val task = RecordingWorker(preferences, codec, audioEnabled)
            worker = task
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "MoqLocalRecorder").apply { isDaemon = true }
            }.also { it.submit(task) }
            diagnostics.event("recording_started", mapOf(
                "mode" to preferences.mode,
                "segment_minutes" to preferences.segmentMinutes,
                "audio" to audioEnabled
            ))
        }
    }

    fun offerVideo(frame: RecordingVideoFrame): Boolean {
        if (!accepting) return false
        return events.offer(Event.Video(frame))
            .also { if (!it) diagnostics.event("recording_video_queue_drop") }
    }

    fun offerAudio(pcm: ByteArray, timestampUs: Long, channels: Int): Boolean {
        if (!accepting) return false
        return events.offer(Event.Audio(pcm, timestampUs, channels))
            .also { if (!it) diagnostics.event("recording_audio_queue_drop") }
    }

    fun stop() {
        val task = synchronized(lock) {
            if (!accepting) return
            accepting = false
            events.offer(Event.Stop)
            worker
        }
        executor?.shutdown()
        runCatching { executor?.awaitTermination(2, TimeUnit.SECONDS) }
        if (task != null && task.isRunning) task.close()
        executor = null
        worker = null
        diagnostics.event("recording_stopped")
    }

    override fun close() = stop()

    private inner class RecordingWorker(
        private val preferences: RecordingPreferences,
        private val codec: VideoCodec,
        private val audioExpected: Boolean
    ) : Runnable {
        private val root = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "recordings"
        )
        private val segmentDurationUs = preferences.segmentMinutes * 60L * 1_000_000L
        private val pendingVideo = ArrayDeque<RecordingVideoFrame>()
        private val pendingAudio = ArrayDeque<AudioSample>()
        private var videoFormat: MediaFormat? = null
        private var audioFormat: MediaFormat? = null
        private var muxer: MediaMuxer? = null
        private var videoTrack = -1
        private var audioTrack = -1
        private var segmentStartUs = Long.MIN_VALUE
        private var segmentIndex = 0
        private var lastVideoPts = Long.MIN_VALUE
        private var lastAudioPts = Long.MIN_VALUE
        private var bytesWritten = 0L
        private var aac: AacEncoder? = null
        @Volatile var isRunning = false

        override fun run() {
            isRunning = true
            try {
                check(root.isDirectory || root.mkdirs()) { "Cannot create recording directory" }
                while (true) {
                    when (val event = events.take()) {
                        is Event.Video -> processVideo(event.frame)
                        is Event.Audio -> processAudio(event)
                        Event.Stop -> break
                    }
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                close()
                isRunning = false
            }
        }

        private fun processVideo(frame: RecordingVideoFrame) {
            if (!hasStorage()) return
            if (videoFormat == null && frame.accessUnit.keyframe) {
                videoFormat = videoFormat(frame)
                pendingVideo.addLast(frame)
                trimPending()
                startMuxerIfReady()
                return
            }
            if (muxer == null) {
                pendingVideo.addLast(frame)
                trimPending()
                startMuxerIfReady()
                return
            }
            if (frame.accessUnit.keyframe && segmentStartUs != Long.MIN_VALUE &&
                frame.timestampUs - segmentStartUs >= segmentDurationUs) {
                closeSegment()
                startMuxerIfReady()
            }
            writeVideo(frame)
        }

        private fun processAudio(event: Event.Audio) {
            if (!hasStorage()) return
            if (aac == null) aac = AacEncoder(event.channels, ::onAudioFormat, ::writeAudio)
            aac?.encode(event.pcm, event.timestampUs)
            startMuxerIfReady()
        }

        private fun onAudioFormat(format: MediaFormat) {
            audioFormat = format
            startMuxerIfReady()
        }

        private fun writeVideo(frame: RecordingVideoFrame) {
            val target = muxer ?: return
            if (frame.accessUnit.data.isEmpty()) return
            val timestamp = frame.timestampUs.coerceAtLeast(lastVideoPts + 1L)
            val info = MediaCodec.BufferInfo().apply {
                set(0, frame.accessUnit.data.size, timestamp, if (frame.accessUnit.keyframe)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            target.writeSampleData(videoTrack, ByteBuffer.wrap(frame.accessUnit.data), info)
            lastVideoPts = timestamp
            updateBytes()
        }

        private fun writeAudio(sample: AudioSample) {
            val target = muxer ?: run {
                pendingAudio.addLast(sample)
                while (pendingAudio.size > 80) pendingAudio.removeFirst()
                return
            }
            val timestamp = sample.timestampUs.coerceAtLeast(lastAudioPts + 1L)
            val info = MediaCodec.BufferInfo().apply {
                set(0, sample.data.size, timestamp, sample.flags)
            }
            target.writeSampleData(audioTrack, ByteBuffer.wrap(sample.data), info)
            lastAudioPts = timestamp
            updateBytes()
        }

        private fun startMuxerIfReady() {
            if (muxer != null || videoFormat == null || (audioExpected && audioFormat == null)) return
            if (!hasStorage()) return
            val video = videoFormat ?: return
            val file = nextFile()
            val created = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrack = created.addTrack(video)
            audioTrack = audioFormat?.let(created::addTrack) ?: -1
            created.start()
            muxer = created
            segmentStartUs = Long.MIN_VALUE
            lastVideoPts = Long.MIN_VALUE
            lastAudioPts = Long.MIN_VALUE
            mutableState.value = mutableState.value.copy(
                active = true, segmentIndex = segmentIndex, currentFile = file.absolutePath,
                availableBytes = availableBytes()
            )
            diagnostics.event("recording_segment_started", mapOf("file" to file.name, "segment" to segmentIndex))
            while (pendingVideo.isNotEmpty()) writePendingVideo(pendingVideo.removeFirst())
            while (pendingAudio.isNotEmpty()) writeAudio(pendingAudio.removeFirst())
        }

        private fun writePendingVideo(frame: RecordingVideoFrame) {
            if (frame.accessUnit.keyframe && segmentStartUs == Long.MIN_VALUE) segmentStartUs = frame.timestampUs
            writeVideo(frame)
        }

        private fun closeSegment() {
            val file = mutableState.value.currentFile
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
            if (file != null) mutableState.value = mutableState.value.copy(lastFile = file, currentFile = null)
            segmentIndex++
            bytesWritten = 0L
            diagnostics.event("recording_segment_closed", mapOf("file" to file, "segment" to segmentIndex - 1))
        }

        fun close() {
            closeSegment()
            runCatching { aac?.close() }
            aac = null
            mutableState.value = mutableState.value.copy(active = false, currentFile = null)
        }

        private fun fail(error: Throwable) {
            diagnostics.error("recording_failed", error)
            mutableState.value = mutableState.value.copy(error = error.javaClass.simpleName, active = false)
            accepting = false
        }

        private fun trimPending() {
            while (pendingVideo.size > 120) pendingVideo.removeFirst()
        }

        private fun hasStorage(): Boolean {
            val available = availableBytes() ?: return true
            mutableState.value = mutableState.value.copy(availableBytes = available)
            if (available < MIN_FREE_BYTES) {
                fail(IllegalStateException("Recording storage is below the safety threshold"))
                return false
            }
            return true
        }

        private fun availableBytes(): Long? = runCatching { StatFs(root.absolutePath).availableBytes }.getOrNull()

        private fun nextFile(): File = File(root, "MoQPublisher-${System.currentTimeMillis()}-${segmentIndex}.mp4")

        private fun videoFormat(frame: RecordingVideoFrame): MediaFormat {
            val format = MediaFormat.createVideoFormat(
                if (frame.codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
                frame.width, frame.height
            )
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frame.framerate)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(requireNotNull(codecConfig(frame.headers, frame.codec, 0))))
            codecConfig(frame.headers, frame.codec, 1)?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            if (frame.codec == VideoCodec.H265) codecConfig(frame.headers, frame.codec, 2)?.let {
                format.setByteBuffer("csd-2", ByteBuffer.wrap(it))
            }
            return format
        }

        private fun codecConfig(headers: ByteArray, codec: VideoCodec, index: Int): ByteArray? {
            val types = if (codec == VideoCodec.H265) intArrayOf(32, 33, 34) else intArrayOf(7, 8)
            val units = splitAnnexB(headers).filter { unit ->
                val type = if (codec == VideoCodec.H265) (unit.firstOrNull()?.toInt()?.ushr(1) ?: -1) and 0x3f
                else (unit.firstOrNull()?.toInt() ?: -1) and 0x1f
                type == types.getOrNull(index)
            }
            return units.firstOrNull()
        }

        private fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            var start = -1
            var codeEnd = 0
            fun codeAt(index: Int): Int = when {
                index + 3 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte() -> 3
                index + 4 <= data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                    data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte() -> 4
                else -> 0
            }
            for (index in data.indices) {
                val code = codeAt(index)
                if (code > 0) {
                    if (start >= 0 && index > codeEnd) result += data.copyOfRange(codeEnd, index)
                    start = index
                    codeEnd = index + code
                }
            }
            if (start >= 0 && codeEnd < data.size) result += data.copyOfRange(codeEnd, data.size)
            return result
        }

        private fun updateBytes() {
            val file = mutableState.value.currentFile ?: return
            bytesWritten = runCatching { File(file).length() }.getOrDefault(bytesWritten)
            mutableState.value = mutableState.value.copy(bytesWritten = bytesWritten)
        }
    }

    private data class AudioSample(val data: ByteArray, val timestampUs: Long, val flags: Int)

    private class AacEncoder(
        channels: Int,
        private val onFormat: (MediaFormat) -> Unit,
        private val onSample: (AudioSample) -> Unit
    ) : AutoCloseable {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val bufferInfo = MediaCodec.BufferInfo()
        private var closed = false

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioSettings.SAMPLE_RATE, channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else 128_000)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AudioSettings.frameBytes(if (channels == 1) AudioChannel.MONO else AudioChannel.STEREO))
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun encode(pcm: ByteArray, timestampUs: Long) {
            if (closed) return
            val input = codec.dequeueInputBuffer(0)
            if (input >= 0) {
                codec.getInputBuffer(input)?.let { buffer ->
                    buffer.clear()
                    buffer.put(pcm)
                    codec.queueInputBuffer(input, 0, pcm.size, timestampUs, 0)
                }
            }
            drain()
        }

        private fun drain() {
            while (!closed) {
                when (val index = codec.dequeueOutputBuffer(bufferInfo, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                    else -> if (index >= 0) {
                        if (bufferInfo.size > 0) codec.getOutputBuffer(index)?.let { buffer ->
                            val data = ByteArray(bufferInfo.size)
                            buffer.position(bufferInfo.offset)
                            buffer.get(data)
                            onSample(AudioSample(data, bufferInfo.presentationTimeUs, bufferInfo.flags))
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    companion object {
        const val MIN_FREE_BYTES = 256L * 1024L * 1024L
    }
}
