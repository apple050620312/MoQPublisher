package com.sangege.moqpublisher

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import dev.moq.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One worker owns the MP4 muxer and AAC encoder for the full capture session. */
internal class LocalRecordingManager(private val context: Context, private val diagnostics: AppDiagnostics) {
    private sealed interface Event {
        data class Video(val frame: RecordingVideoFrame) : Event
        data class Audio(val pcm: ByteArray, val timestampUs: Long, val channels: Int) : Event
        data class Stop(val error: UiMessage?) : Event
    }

    private val mutableState = MutableStateFlow(RecordingState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    @Volatile private var worker: RecordingWorker? = null
    val isRecording get() = worker?.accepting == true
    private val root get() = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "recordings")

    fun start(preferences: RecordingPreferences, audioEnabled: Boolean): Boolean = synchronized(lock) {
        if (worker != null) return@synchronized false
        mutableState.value = RecordingState(mode = preferences.mode, lastFile = mutableState.value.lastFile)
        if (preferences.mode.records) {
            val task = RecordingWorker(preferences.segmentMinutes * 60_000_000L, audioEnabled)
            worker = task
            try {
                Thread(task, "MoqLocalRecorder").apply { isDaemon = true }.start()
            } catch (error: Exception) {
                worker = null
                diagnostics.error("recording_worker_start_failed", error)
                mutableState.value = mutableState.value.copy(error = UiMessage(R.string.recording_error))
                return@synchronized false
            }
        }
        true
    }

    fun offerVideo(frame: RecordingVideoFrame): Boolean = worker?.offer(Event.Video(frame)) == true

    fun offerAudio(pcm: ByteArray, timestampUs: Long, channels: Int) {
        val task = worker ?: return
        if (task.accepting) task.offer(Event.Audio(pcm.clone(), timestampUs, channels))
    }

    fun stop() {
        val task = worker ?: return
        task.stop()
        if (!task.finished.await(3, TimeUnit.SECONDS)) diagnostics.event("recording_finalize_pending")
    }

    fun recordings(): List<String> = root.listFiles { file ->
        file.isFile && file.name.startsWith("MoQPublisher-") && file.extension.equals("mp4", true)
    }.orEmpty().sortedByDescending { it.lastModified() }.map { it.name }

    fun export(name: String, destination: Uri) {
        require(name in recordings())
        val directory = root.canonicalFile
        val source = File(directory, name).canonicalFile
        require(source.parentFile == directory)
        source.inputStream().use { input ->
            checkNotNull(context.contentResolver.openOutputStream(destination, "w")).use { output -> input.copyTo(output) }
        }
    }

    private inner class RecordingWorker(private val segmentDurationUs: Long, private val audioExpected: Boolean) : Runnable {
        // Always leave one slot for Stop, even if capture outpaces the disk.
        private val events = ArrayBlockingQueue<Event>(241)
        val finished = CountDownLatch(1)
        @Volatile var accepting = true
            private set
        private val pendingVideo = ArrayDeque<RecordingVideoFrame>()
        private val pendingAudio = ArrayDeque<AudioSample>()
        private var audioFormat: MediaFormat? = null
        private var aac: AacEncoder? = null
        private var audioChannels = 0
        private var segment: Segment? = null
        private var segmentCount = 0
        private var nextStorageCheck = 0L

        fun offer(event: Event): Boolean = synchronized(events) {
            if (!accepting) return@synchronized false
            if (events.remainingCapacity() == 1) {
                stop(UiMessage(R.string.recording_overloaded))
                return@synchronized false
            }
            events.offer(event)
        }

        fun stop(error: UiMessage? = null) {
            synchronized(events) {
                if (accepting) {
                    accepting = false
                    check(events.offer(Event.Stop(error)))
                }
            }
        }

        override fun run() {
            var problem: UiMessage? = null
            try {
                check(root.isDirectory || root.mkdirs()) { "Cannot create recording directory" }
                checkStorage()
                diagnostics.event("recording_started", mapOf("segment_duration_us" to segmentDurationUs, "audio" to audioExpected))
                var recording = true
                while (recording) {
                    when (val event = events.take()) {
                        is Event.Video -> { checkStorage(); processVideo(event.frame) }
                        is Event.Audio -> { checkStorage(); processAudio(event) }
                        is Event.Stop -> {
                            problem = event.error
                            aac?.finish()
                            tryOpenSegment(waitForAudio = false)
                            recording = false
                        }
                    }
                }
            } catch (error: Exception) {
                diagnostics.error("recording_failed", error)
                problem = (error as? UiException)?.uiMessage ?: problem ?: UiMessage(R.string.recording_error)
            } finally {
                synchronized(events) { accepting = false; events.clear() }
                try {
                    finishSegment()
                } catch (error: Exception) {
                    diagnostics.error("recording_finalize_failed", error)
                    if (problem == null) problem = UiMessage(R.string.recording_error)
                } finally {
                    aac?.close()
                    synchronized(lock) {
                        mutableState.value = mutableState.value.copy(active = false, currentFile = null, error = problem)
                        if (worker === this) worker = null
                    }
                    diagnostics.event("recording_stopped", mapOf("segments" to segmentCount, "error" to problem?.resource))
                    finished.countDown()
                }
            }
        }

        private fun processVideo(frame: RecordingVideoFrame) {
            val current = segment
            if (current != null) {
                val formatChanged = !frame.matchesFormat(current.first)
                val elapsed = frame.timestampUs - current.first.timestampUs
                val audioReady = current.audioTrack < 0 && audioFormat != null && pendingAudio.isNotEmpty()
                if (!formatChanged && (!frame.accessUnit.keyframe || elapsed < segmentDurationUs && !audioReady)) {
                    current.writeVideo(frame)
                    return
                }
                finishSegment()
            }
            val first = pendingVideo.peekFirst()
            if (first != null && (!first.matchesFormat(frame) || pendingVideo.size >= 160)) pendingVideo.clear()
            if (pendingVideo.isEmpty() && !frame.accessUnit.keyframe) return
            pendingVideo.addLast(frame)
            tryOpenSegment()
        }

        private fun processAudio(event: Event.Audio) {
            if (event.channels != audioChannels) {
                aac?.let {
                    it.finish()
                    it.close()
                    aac = null
                    finishSegment()
                    pendingVideo.clear()
                    pendingAudio.clear()
                    audioFormat = null
                }
                audioChannels = event.channels
                aac = AacEncoder(event.channels, { audioFormat = it }, ::writeAudio)
            }
            aac?.encode(event.pcm, event.timestampUs)
            tryOpenSegment()
        }

        private fun writeAudio(sample: AudioSample) {
            val current = segment
            if (current != null && current.audioTrack >= 0) {
                current.writeAudio(sample)
            } else {
                pendingAudio.addLast(sample)
                if (pendingAudio.size > 100) pendingAudio.removeFirst()
            }
        }

        private fun tryOpenSegment(waitForAudio: Boolean = true) {
            if (segment != null) return
            val first = pendingVideo.peekFirst() ?: return
            val last = pendingVideo.last
            val audio = audioFormat?.takeIf { pendingAudio.peekLast()?.timestampUs?.let { pts -> pts >= first.timestampUs } == true }
            if (waitForAudio && audioExpected && audio == null && last.timestampUs - first.timestampUs < 1_200_000L) return
            val next = Segment.open(root, first, audio)
            segment = next
            segmentCount++
            mutableState.value = mutableState.value.copy(
                active = true, currentFile = next.destination.absolutePath, segmentIndex = segmentCount, bytesWritten = 0
            )
            diagnostics.event("recording_segment_started", mapOf("file" to next.destination.name, "segment" to segmentCount))
            while (pendingVideo.isNotEmpty()) next.writeVideo(pendingVideo.removeFirst())
            if (next.audioTrack >= 0) while (pendingAudio.isNotEmpty()) next.writeAudio(pendingAudio.removeFirst())
        }

        private fun finishSegment() {
            val current = segment ?: return
            segment = null
            current.finish()
            val file = current.destination
            mutableState.value = mutableState.value.copy(lastFile = file.absolutePath, currentFile = null)
            diagnostics.event("recording_segment_closed", mapOf("file" to file.name, "segment" to segmentCount))
        }

        private fun checkStorage() {
            val now = SystemClock.elapsedRealtime()
            if (now < nextStorageCheck) return
            nextStorageCheck = now + 1_000L
            val available = StatFs(root.absolutePath).availableBytes
            mutableState.value = mutableState.value.copy(availableBytes = available, bytesWritten = segment?.bytesWritten ?: 0)
            if (available < MIN_FREE_BYTES) throw UiException(UiMessage(R.string.recording_storage_low))
        }
    }

    private class Segment(
        private val muxer: MediaMuxer, private val temporary: File, val destination: File,
        val first: RecordingVideoFrame, private val videoTrack: Int, val audioTrack: Int
    ) {
        private val info = MediaCodec.BufferInfo()
        private var lastVideoPts = -1L
        private var lastAudioPts = -1L
        var bytesWritten = 0L
            private set

        fun writeVideo(frame: RecordingVideoFrame) {
            val flags = if (frame.accessUnit.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            lastVideoPts = write(videoTrack, frame.accessUnit.data, frame.timestampUs, lastVideoPts, flags)
        }

        fun writeAudio(sample: AudioSample) {
            lastAudioPts = write(audioTrack, sample.data, sample.timestampUs, lastAudioPts, 0)
        }

        private fun write(track: Int, data: ByteArray, timestampUs: Long, lastPts: Long, flags: Int): Long {
            if (data.isEmpty() || timestampUs < first.timestampUs) return lastPts
            val pts = (timestampUs - first.timestampUs).coerceAtLeast(lastPts + 1L)
            info.set(0, data.size, pts, flags)
            muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
            bytesWritten += data.size
            return pts
        }

        fun finish() {
            try {
                try { muxer.stop() } finally { muxer.release() }
                check(temporary.renameTo(destination)) { "Cannot finalize recording file" }
            } catch (error: Exception) {
                // A failed muxer must not advertise its file as a completed MP4.
                temporary.delete()
                throw error
            }
        }

        companion object {
            fun open(root: File, first: RecordingVideoFrame, audioFormat: MediaFormat?): Segment {
                val hevc = first.codec == VideoCodec.H265
                val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                val videoFormat = MediaFormat.createVideoFormat(mime, first.width, first.height).apply {
                    setInteger(MediaFormat.KEY_FRAME_RATE, first.framerate)
                    AnnexB.mp4CodecSpecificData(first.headers, hevc).forEachIndexed { index, data ->
                        setByteBuffer("csd-$index", ByteBuffer.wrap(data))
                    }
                }
                val temporary = File.createTempFile("MoQPublisher-${System.currentTimeMillis()}-", ".partial", root)
                val destination = File(root, "${temporary.nameWithoutExtension}.mp4")
                var muxer: MediaMuxer? = null
                try {
                    val created = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = created
                    val videoTrack = created.addTrack(videoFormat)
                    val audioTrack = audioFormat?.let(created::addTrack) ?: -1
                    created.start()
                    return Segment(created, temporary, destination, first, videoTrack, audioTrack)
                } catch (error: Exception) {
                    runCatching { muxer?.release() }
                    temporary.delete()
                    throw error
                }
            }
        }
    }

    private data class AudioSample(val data: ByteArray, val timestampUs: Long)

    private class AacEncoder(channels: Int, val onFormat: (MediaFormat) -> Unit, val onSample: (AudioSample) -> Unit) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val info = MediaCodec.BufferInfo()
        private var lastTimestampUs = 0L

        init {
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioSettings.SAMPLE_RATE, channels)
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else 128_000)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (error: Exception) {
                runCatching { codec.release() }
                throw error
            }
        }

        fun encode(pcm: ByteArray, timestampUs: Long) {
            drain()
            val input = codec.dequeueInputBuffer(20_000)
            check(input >= 0) { "AAC encoder did not accept PCM" }
            val buffer = checkNotNull(codec.getInputBuffer(input))
            buffer.clear()
            buffer.put(pcm)
            lastTimestampUs = timestampUs
            codec.queueInputBuffer(input, 0, pcm.size, timestampUs, 0)
            drain()
        }

        fun finish() {
            drain()
            val input = codec.dequeueInputBuffer(50_000)
            check(input >= 0) { "AAC encoder did not accept end of stream" }
            codec.queueInputBuffer(input, 0, 0, lastTimestampUs + 20_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val deadline = SystemClock.elapsedRealtime() + 500
            while (SystemClock.elapsedRealtime() < deadline) {
                if (drain(10_000)) return
            }
            error("AAC encoder did not drain end of stream")
        }

        private fun drain(waitUs: Long = 0L): Boolean {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, waitUs)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = checkNotNull(codec.getOutputBuffer(index))
                                val bytes = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(bytes)
                                onSample(AudioSample(bytes, info.presentationTimeUs))
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        }

        fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    companion object {
        const val MIN_FREE_BYTES = 256L * 1024L * 1024L
    }
}
