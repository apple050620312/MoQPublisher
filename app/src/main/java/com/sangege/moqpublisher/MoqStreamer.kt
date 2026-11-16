package com.sangege.moqpublisher

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import android.view.Surface
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import dev.moq.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import uniffi.moq.MoqException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private const val TAG = "MoqStreamer"

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data class Live(
        val rttMs: Long? = null,
        val sendRateKbps: Long? = null,
        val bytesSentMb: Double? = null,
        val publishedFps: Double? = null,
        val videoBitrateBps: Long? = null,
        val epoch: ULong = 1u,
        val phase: RelayConnectionPhase = RelayConnectionPhase.CONNECTED,
        val reconnectCount: Long = 0,
        val endpointDraft: Int? = null
    ) : StreamState
    data class Error(val message: UiMessage) : StreamState
}

/** Coordinates the MoQ session, camera frames, microphone capture and statistics. */
class MoqStreamer(private val context: Context, private val diagnostics: AppDiagnostics) {

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()
    private val _audioCaptureStatus = MutableStateFlow<AudioCaptureStatus?>(null)
    val audioCaptureStatus: StateFlow<AudioCaptureStatus?> = _audioCaptureStatus.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycleLock = Any()
    private val streamLock = Any()
    // Video codec work must never stall microphone capture/publication.
    private val videoLock = Any()
    private val audioLock = Any()

    @Volatile
    private var moqSession: Moq? = null

    private var sessionBandwidth: Bandwidth? = null
    private var videoFramePacer: VideoFramePacer? = null
    private var videoBitrateControl: VideoBitrateControl? = null
    private var hardwareEncoder: VideoEncoder? = null
    @Volatile
    private var videoDemand: TrackDemand? = null
    private var videoWasUsed = false
    private var videoTimestampMapper: VideoTimestampMapper? = null
    @Volatile
    private var videoGeneration = 0
    private var bitrateAdaptationEnabled = true

    private var videoPublishQueue: VideoPublishQueue? = null
    private var videoPublishExecutor: ExecutorService? = null
    private var videoPublishFuture: Future<*>? = null
    private var audioPublishQueue: AudioPublishQueue? = null
    private var audioPublishExecutor: ExecutorService? = null
    private var audioPublishFuture: Future<*>? = null
    private var audioCaptureExecutor: ExecutorService? = null
    private var audioCaptureFuture: Future<*>? = null
    private val audioHealth = AudioHealthMetrics()

    @Volatile
    private var broadcastProducer: BroadcastProducer? = null

    @Volatile
    private var videoProducer: MediaProducer? = null

    private var audioRecord: AudioRecord? = null
    private var statsJob: Job? = null
    private var broadcastJob: Job? = null

    private val isBroadcasting = AtomicBoolean(false)
    @Volatile
    private var closed = false
    private var startNanoTime: Long = 0L

    private var currentVideoWidth: Int = 0
    private var currentVideoHeight: Int = 0
    private var videoTrackCounter: Int = 0
    private var configuredCodec: VideoCodec = VideoCodec.H264
    private var configuredFramerate: Int = 30
    private var configuredBitrate: Long = VideoBitrateSettings.DEFAULT_BPS
    private var configuredAudioChannel = AudioChannel.MONO
    private var configuredAudioQuality = AudioQuality.STANDARD
    private var configuredAudioGain = AudioSettings.DEFAULT_GAIN
    private var configuredProvider = RelayProvider.MOQ_PRO
    private var configuredDraft: Int? = null
    internal var beforeVideoEncoderClose: () -> Unit = {}
    internal val canStart: Boolean get() = synchronized(lifecycleLock) { !closed && broadcastJob == null }

    fun startBroadcast(
        relayUrl: String,
        streamPath: String,
        tlsVerify: Boolean,
        enableAudio: Boolean,
        audioChannel: AudioChannel = AudioChannel.MONO,
        audioQuality: AudioQuality = AudioQuality.STANDARD,
        audioGainPercent: Int = AudioSettings.DEFAULT_GAIN,
        codec: VideoCodec = VideoCodec.H264,
        framerate: Int = 30,
        bitrateBps: Long = VideoBitrateSettings.DEFAULT_BPS,
        adaptiveBitrate: Boolean = true,
        provider: RelayProvider = RelayProvider.MOQ_PRO,
        protocolDraft: Int? = null
    ) {
        synchronized(lifecycleLock) {
            // A cancelled job still owns its resources until cleanup completes.
            if (closed || broadcastJob != null) return

            diagnostics.metrics.reset()
            audioHealth.reset()
            diagnostics.event("broadcast_start_requested", mapOf(
                "relay" to RelayUrl.maskJwt(relayUrl), "path" to streamPath, "tls_verify" to tlsVerify,
                "provider" to provider, "endpoint_draft" to protocolDraft,
                "audio" to enableAudio, "codec" to codec, "fps" to framerate, "bitrate_bps" to bitrateBps,
                "adaptive_bitrate" to adaptiveBitrate,
                "audio_channel" to audioChannel, "audio_gain_percent" to audioGainPercent,
                "network" to diagnostics.networkDescription()
            ))

            configuredCodec = codec
            configuredFramerate = framerate.coerceIn(1, 120)
            configuredBitrate = bitrateBps.coerceIn(VideoBitrateSettings.MIN_BPS, Int.MAX_VALUE.toLong())
            configuredAudioChannel = audioChannel
            configuredAudioQuality = audioQuality
            configuredAudioGain = AudioSettings.normalizeGain(audioGainPercent)
            configuredProvider = provider
            configuredDraft = protocolDraft
            _audioCaptureStatus.value = null
            videoBitrateControl = VideoBitrateControl(configuredBitrate, if (enableAudio) audioQuality.bitrateBps(audioChannel) else 0L)
            bitrateAdaptationEnabled = adaptiveBitrate
            _streamState.value = StreamState.Connecting
            broadcastJob = scope.launch {
                runBroadcast(
                    relayUrl = relayUrl.trim(),
                    streamPath = streamPath.trim(),
                    tlsVerify = tlsVerify,
                    enableAudio = enableAudio
                )
            }
        }
    }

    private suspend fun runBroadcast(
        relayUrl: String,
        streamPath: String,
        tlsVerify: Boolean,
        enableAudio: Boolean
    ) {
        var terminalState: StreamState = StreamState.Idle
        val handovers = Channel<Unit>(Channel.CONFLATED)
        var monitor: DefaultNetworkMonitor? = null
        try {
            monitor = DefaultNetworkMonitor(context) { handovers.trySend(Unit) }
            var reconnectBase = 0L
            while (currentCoroutineContext().isActive) {
                coroutineScope {
                    val connection = async {
                        runConnectedBroadcast(relayUrl, streamPath, tlsVerify, enableAudio, reconnectBase)
                    }
                    select<Unit> {
                        connection.onAwait { }
                        handovers.onReceive {
                            diagnostics.event("default_network_changed", mapOf("network" to diagnostics.networkDescription()))
                        }
                    }
                    _streamState.update { current ->
                        (current as? StreamState.Live)?.copy(phase = RelayConnectionPhase.RECONNECTING)
                            ?: StreamState.Live(phase = RelayConnectionPhase.RECONNECTING, endpointDraft = configuredDraft)
                    }
                    connection.cancelAndJoin()
                }
                reconnectBase = ((_streamState.value as? StreamState.Live)?.reconnectCount ?: reconnectBase) + 1
                cleanup()
            }
        } catch (e: TimeoutCancellationException) {
            terminalState = StreamState.Error(UiMessage(R.string.error_relay_timeout))
            _streamState.value = terminalState
            diagnostics.event("relay_connect_timeout")
        } catch (e: CancellationException) {
            diagnostics.event("broadcast_cancelled")
            terminalState = _streamState.value as? StreamState.Error ?: StreamState.Idle
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Broadcast failed: ${DiagnosticRedactor.redact(e.stackTraceToString())}")
            diagnostics.error("broadcast_error", e)
            terminalState = StreamState.Error(formatError(e, R.string.error_broadcast))
            _streamState.value = terminalState
        } finally {
            monitor?.close()
            handovers.close()
            cleanup()
            synchronized(lifecycleLock) {
                if (terminalState is StreamState.Idle) _streamState.value = StreamState.Idle
                broadcastJob = null
            }
        }
    }

    private suspend fun runConnectedBroadcast(
        relayUrl: String, streamPath: String, tlsVerify: Boolean, enableAudio: Boolean, reconnectBase: Long
    ): Unit = coroutineScope {
            Log.d(TAG, "Connecting to relay: ${RelayUrl.maskJwt(relayUrl)} (tlsVerify=$tlsVerify)...")
            currentCoroutineContext().ensureActive()

            val moq = withTimeout(RelayConnectionPolicy.CONNECT_TIMEOUT_MS) { Moq.connect(
                url = relayUrl,
                tlsVerify = tlsVerify,
                // Cloudflare serves WebTransport/QUIC. Keep the existing moq.pro fallback.
                websocketEnabled = configuredProvider == RelayProvider.MOQ_PRO,
                reconnect = true,
                backoff = Backoff(initialUs = RelayConnectionPolicy.INITIAL_BACKOFF_US, multiplier = 2u,
                    maxUs = RelayConnectionPolicy.MAX_BACKOFF_US, timeoutUs = RelayConnectionPolicy.RETRY_WINDOW_US)
            ).also { connected -> synchronized(streamLock) { moqSession = connected } } }
            currentCoroutineContext().ensureActive()
            _streamState.value = StreamState.Live(epoch = moq.epoch(), reconnectCount = reconnectBase, endpointDraft = configuredDraft)
            val bandwidth = synchronized(streamLock) {
                moq.bandwidth().also { sessionBandwidth = it }
            }

            Log.d(TAG, "Connected to MoQ relay. Creating broadcast at '$streamPath'...")
            diagnostics.event("relay_connected", mapOf("epoch" to moq.epoch()))
            val broadcast = synchronized(streamLock) {
                moq.createBroadcast(streamPath).also { broadcastProducer = it }
            }
            currentCoroutineContext().ensureActive()

            // Keep all native media writes off CameraX/AudioRecord callbacks.
            // The queues are bounded so a slow relay can never turn into
            // unbounded live latency.
            startVideoPublisher(broadcast)

            synchronized(streamLock) {
                startNanoTime = System.nanoTime()
                videoFramePacer = VideoFramePacer(configuredFramerate)
                videoTimestampMapper = VideoTimestampMapper()
                isBroadcasting.set(true)
            }

            if (enableAudio) startAudioCapture(broadcast, bandwidth)

            currentCoroutineContext().ensureActive()
            Log.d(TAG, "Announcing broadcast route...")
            broadcast.announce(Route())

            // announce() updates the local origin. It is not a relay acceptance or decode ACK.
            Log.d(TAG, "Local broadcast advertised; waiting for media demand.")
            diagnostics.event("broadcast_advertised", mapOf("epoch" to moq.epoch()))
            startStatsMonitoring(moq)
            launch {
                var lastEpoch = 0uL
                while (isActive) {
                    val status = moq.session.status()
                    val epoch = moq.epoch()
                    diagnostics.event("relay_status", mapOf("status" to status, "epoch" to epoch))
                    when (status) {
                        ConnectionStatus.CONNECTED -> {
                            _streamState.update { current ->
                                if (current is StreamState.Live && epoch != lastEpoch)
                                    RelayConnectionPolicy.reconnected(current, epoch, reconnectBase) else current
                            }
                            if (epoch != lastEpoch) {
                                videoPublishQueue?.waitForKeyframe()
                                synchronized(videoLock) {
                                    hardwareEncoder?.requestKeyframe(((System.nanoTime() - startNanoTime) / 1000L).coerceAtLeast(0))
                                    videoWasUsed = false
                                }
                                lastEpoch = epoch
                            }
                        }
                        ConnectionStatus.DISCONNECTED, ConnectionStatus.MIGRATING -> _streamState.update { current ->
                            if (current is StreamState.Live) current.copy(phase = RelayConnectionPhase.RECONNECTING) else current
                        }
                    }
                }
            }
            awaitCancellation()
    }

    /** Runs MoQ video writes on a dedicated thread, behind a bounded queue. */
    private fun startVideoPublisher(broadcast: BroadcastProducer) {
        val queueCapacity = VideoPublishQueue.capacityFor(configuredFramerate)
        val queue = VideoPublishQueue(capacity = queueCapacity)
        diagnostics.event("video_publish_queue", mapOf("capacity_frames" to queueCapacity, "window_ms" to (queueCapacity * 1_000L / configuredFramerate)))
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "MoqVideoPublisher").apply { isDaemon = true }
        }
        videoPublishQueue = queue
        videoPublishExecutor = executor
        videoPublishFuture = executor.submit {
            var activeProducer: MediaProducer? = null
            var activeGeneration = -1
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val packet = queue.take() ?: break
                    if (!isBroadcasting.get()) continue

                    if (packet.generation != activeGeneration) {
                        val old = activeProducer
                        activeProducer = null
                        old?.let {
                            runCatching { it.finish() }
                            runCatching { it.close() }
                        }
                        synchronized(videoLock) {
                            if (videoProducer === old) {
                                videoProducer = null
                                videoDemand = null
                            }
                        }
                        activeGeneration = packet.generation
                    }

                    if (activeProducer == null) {
                        // A track must begin with a random access unit. Keep
                        // dropping deltas until the hardware encoder emits one.
                        if (!packet.accessUnit.keyframe) {
                            diagnostics.metrics.count(StreamMetrics.Counter.VIDEO_QUEUE_DROP)
                            continue
                        }
                        val producer = broadcast.publishVideo(
                            VideoInit(
                                format = if (packet.codec == VideoCodec.H265) VideoFormat.HEV1 else VideoFormat.AVC3,
                                data = packet.headers,
                                track = packet.trackName,
                                hint = VideoHint(
                                    coded = Dimensions(packet.width.toUInt(), packet.height.toUInt()),
                                    bitrate = packet.bitrateBps.toULong(),
                                    framerate = packet.framerate.toDouble(),
                                    optimizeForLatency = true
                                )
                            )
                        )
                        activeProducer = producer
                        synchronized(videoLock) {
                            if (isBroadcasting.get() && videoGeneration == packet.generation) {
                                videoProducer = producer
                                videoDemand = producer.demand()
                            }
                        }
                        diagnostics.event("video_track_published", mapOf(
                            "track" to packet.trackName,
                            "generation" to packet.generation,
                            "headers_bytes" to packet.headers.size,
                            "width" to packet.width,
                            "height" to packet.height
                        ))
                    }

                    val producer = activeProducer
                    val writeStartedNs = System.nanoTime()
                    try {
                        producer.writeFrame(Frame(
                            payload = packet.accessUnit.data,
                            timestampUs = packet.timestampUs.toULong()
                        ))
                        producer.flush(packet.timestampUs.toULong())
                    } finally {
                        diagnostics.metrics.timing(StreamMetrics.Stage.MOQ_WRITE, System.nanoTime() - writeStartedNs)
                    }
                    diagnostics.metrics.published(packet.timestampUs, startNanoTime, packet.accessUnit.data.size)
                    val requested = videoDemand?.isUsed() == true
                    _streamState.update { current ->
                        if (current is StreamState.Live) RelayConnectionPolicy.mediaWritten(current, requested) else current
                    }
                }
            } catch (_: InterruptedException) {
                // Cleanup interrupts the worker after closing its queue.
            } catch (error: Throwable) {
                if (isBroadcasting.get()) failBroadcast(error)
            } finally {
                activeProducer?.let {
                    runCatching { it.finish() }
                    runCatching { it.close() }
                }
                synchronized(videoLock) {
                    if (videoProducer === activeProducer) {
                        videoProducer = null
                        videoDemand = null
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(broadcast: BroadcastProducer, bandwidth: Bandwidth) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) throw UiException(UiMessage(R.string.error_microphone_permission))

        val sampleRate = AudioSettings.SAMPLE_RATE
        synchronized(audioLock) {
            if (!isBroadcasting.get() || closed) return
            var record: AudioRecord? = null
            var producer: MoqAudioPublisher? = null
            var publisherStarted = false
            try {
                val (actualChannel, createdRecord) = AudioSettings.openCapture(configuredAudioChannel, ::openAudioRecord) {
                    diagnostics.error("audio_stereo_fallback", it)
                }
                record = createdRecord
                val frameBytes = AudioSettings.frameBytes(actualChannel)
                // Account for the actual Opus channel configuration, including a mono fallback.
                synchronized(videoLock) {
                    videoBitrateControl = VideoBitrateControl(configuredBitrate, configuredAudioQuality.bitrateBps(actualChannel))
                }
                Log.d(TAG, "Encoding audio with Opus (48kHz, $actualChannel)...")
                val actualBitrate = configuredAudioQuality.bitrateBps(actualChannel)
                val audio = MoqAudioPublisher.open(broadcast, actualChannel, bandwidth, actualBitrate)
                producer = audio

                val activeRecord = createdRecord
                _audioCaptureStatus.value = AudioCaptureStatus(configuredAudioChannel, actualChannel, configuredAudioGain, actualBitrate)
                diagnostics.event("audio_started", mapOf(
                    "sample_rate" to sampleRate, "frame_bytes" to frameBytes,
                    "channel_requested" to configuredAudioChannel, "channel_actual" to actualChannel,
                    "gain_percent" to configuredAudioGain, "bitrate_bps" to actualBitrate, "quality" to configuredAudioQuality,
                    "group_duration_ms" to MoqAudioPublisher.GROUP_DURATION_MS,
                    "audio_thread_priority" to Process.THREAD_PRIORITY_AUDIO
                ))
                audioRecord = activeRecord
                startAudioPublisher(audio)
                publisherStarted = true
                val captureExecutor = Executors.newSingleThreadExecutor { audioThread(it, "MoqAudioCapture") }
                audioCaptureExecutor = captureExecutor
                audioCaptureFuture = captureExecutor.submit {
                    val buffer = ByteArray(frameBytes)
                    val timeline = AudioTimeline()
                    val hardwareTimestamp = AudioTimestamp()
                    var framesRead = 0L
                    try {
                        while (!Thread.currentThread().isInterrupted && isBroadcasting.get()) {
                            var readTotal = 0
                            while (readTotal < frameBytes && isBroadcasting.get()) {
                                val read = activeRecord.read(buffer, readTotal, frameBytes - readTotal, AudioRecord.READ_BLOCKING)
                                if (Thread.currentThread().isInterrupted || !isBroadcasting.get()) return@submit
                                if (read <= 0) {
                                    diagnostics.event("audio_read_failed", mapOf("code" to read))
                                    throw UiException(UiMessage(R.string.error_audio_capture))
                                }
                                readTotal += read
                            }

                            if (readTotal == frameBytes && isBroadcasting.get()) {
                                framesRead += AudioSettings.SAMPLES_PER_FRAME
                                AudioSettings.applyGain(buffer, configuredAudioGain)
                                val stamp = timeline.next((System.nanoTime() - startNanoTime) / 1000L)
                                audioHealth.captured(buffer)
                                if (framesRead % AudioSettings.SAMPLE_RATE == 0L &&
                                    activeRecord.getTimestamp(hardwareTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                    audioHealth.hardwareClock(framesRead, hardwareTimestamp.framePosition, hardwareTimestamp.nanoTime)
                                }
                                if (isBroadcasting.get()) {
                                    val queue = audioPublishQueue ?: return@submit
                                    val result = queue.offer(EncodedAudioPacket(
                                        timestampUs = stamp.timestampUs,
                                        data = buffer.clone(),
                                        resetEpoch = stamp.reset,
                                        enqueuedAtNs = System.nanoTime()
                                    ))
                                    audioHealth.queueDepth(queue.depth())
                                    if (result == AudioPublishQueue.OfferResult.DROPPED) {
                                        diagnostics.metrics.count(StreamMetrics.Counter.AUDIO_QUEUE_DROP)
                                    }
                                    if (result == AudioPublishQueue.OfferResult.CLOSED) return@submit
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        if (!Thread.currentThread().isInterrupted && isBroadcasting.get()) failBroadcast(e)
                    }
                }
            } catch (e: Throwable) {
                if (audioRecord === record) audioRecord = null
                runCatching { record?.stop() }
                runCatching { record?.release() }
                if (!publisherStarted) runCatching { producer?.close() }
                if (e is CancellationException) throw e
                throw if (e is UiException) e else UiException(UiMessage(R.string.error_audio_capture), e)
            }
        }
    }

    /** AudioRecord stays responsive while the native Opus/MoQ writer drains. */
    private fun startAudioPublisher(audio: MoqAudioPublisher) {
        val queue = AudioPublishQueue(capacity = 4)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            audioThread(runnable, "MoqAudioPublisher")
        }
        audioPublishQueue = queue
        audioPublishExecutor = executor
        audioPublishFuture = executor.submit { runBlocking {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val packet = queue.take() ?: break
                    if (!isBroadcasting.get()) continue
                    if (packet.resetEpoch) {
                        diagnostics.metrics.count(StreamMetrics.Counter.AUDIO_RESET)
                    }
                    val writeStartedNs = System.nanoTime()
                    diagnostics.metrics.timing(StreamMetrics.Stage.AUDIO_QUEUE_WAIT, writeStartedNs - packet.enqueuedAtNs)
                    val published = audio.writeSuspending(packet)
                    audioHealth.published(published.bytes, published.groupClosed)
                    diagnostics.metrics.timing(StreamMetrics.Stage.AUDIO_WRITE, System.nanoTime() - writeStartedNs)
                    diagnostics.metrics.count(StreamMetrics.Counter.AUDIO_PACKET)
                }
            } catch (_: InterruptedException) {
                // Cleanup interrupts the worker after closing its queue.
            } catch (error: Throwable) {
                if (isBroadcasting.get()) failBroadcast(error)
            } finally {
                audio.close()
            }
        } }
    }

    private fun audioThread(worker: Runnable, name: String): Thread = Thread({
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            .onFailure { diagnostics.error("audio_thread_priority_failed", it) }
        worker.run()
    }, name).apply { isDaemon = true }

    /** Probe and start the requested hardware format before publishing its channel count to MoQ. */
    @SuppressLint("MissingPermission")
    private fun openAudioRecord(channel: AudioChannel): AudioRecord {
        val mask = if (channel == AudioChannel.STEREO) AndroidAudioFormat.CHANNEL_IN_STEREO else AndroidAudioFormat.CHANNEL_IN_MONO
        val format = AndroidAudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(AudioSettings.SAMPLE_RATE, mask, format)
        check(minimum > 0) { "Unsupported microphone format: $channel ($minimum)" }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, AudioSettings.SAMPLE_RATE, mask, format,
            maxOf(minimum, AudioSettings.frameBytes(channel) * 4)
        )
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED && record.channelCount == channel.count &&
                record.sampleRate == AudioSettings.SAMPLE_RATE) { "Microphone format unavailable: $channel" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone capture did not start" }
            return record
        } catch (e: Throwable) {
            runCatching { record.stop() }
            record.release()
            throw e
        }
    }

    fun onVideoFrame(imageProxy: ImageProxy) {
        val callbackStartedNs = System.nanoTime()
        var recordTiming = false
        try {
            val width = imageProxy.width
            val height = imageProxy.height
            if (width <= 0 || height <= 0) return

            if (!isBroadcasting.get()) return
            recordTiming = true
            diagnostics.metrics.count(StreamMetrics.Counter.CAMERA)

            synchronized(videoLock) {
                if (!isBroadcasting.get()) return
                if (broadcastProducer == null) return
                // Sensor timestamps describe the capture cadence. Callback
                // arrival times jitter when Android schedules camera/codec work.
                if (videoFramePacer?.shouldEncode(imageProxy.imageInfo.timestamp) != true) {
                    diagnostics.metrics.count(StreamMetrics.Counter.PACED_DROP)
                    return
                }
                val current = hardwareEncoder
                if (current == null || currentVideoWidth != width || currentVideoHeight != height) {
                    diagnostics.event("camera_frame_format", mapOf(
                        "width" to width, "height" to height, "crop" to imageProxy.cropRect,
                        "rotation" to imageProxy.imageInfo.rotationDegrees, "format" to imageProxy.format,
                        "planes" to imageProxy.planes.joinToString { "row:${it.rowStride}/pixel:${it.pixelStride}" }
                    ))
                    createVideoProducerLocked(width, height)
                }
                val encoder = hardwareEncoder as? HardwareVideoEncoder ?: return
                val stamp = videoTimestampMapper?.map(
                    sensorTimestampNs = imageProxy.imageInfo.timestamp,
                    callbackTimeNs = callbackStartedNs,
                    streamStartNs = startNanoTime
                ) ?: VideoTimestampMapper.Stamp(
                    timestampUs = ((callbackStartedNs - startNanoTime) / 1_000L).coerceAtLeast(0L),
                    reset = false
                )
                if (stamp.reset) diagnostics.metrics.count(StreamMetrics.Counter.VIDEO_TIMESTAMP_RESET)
                val ptsUs = stamp.timestampUs
                val used = videoDemand?.isUsed() == true
                if (used && !videoWasUsed) {
                    encoder.requestKeyframe(ptsUs)
                }
                videoWasUsed = used
                encoder.write(imageProxy, ptsUs)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error encoding/writing video frame: ${DiagnosticRedactor.redact(e.stackTraceToString())}")
            diagnostics.error("video_error", e)
            if (isBroadcasting.get()) {
                _streamState.value = StreamState.Error(formatError(e, R.string.error_video))
                stopBroadcast()
            }
        } finally {
            imageProxy.close()
            if (recordTiming) diagnostics.metrics.timing(StreamMetrics.Stage.VIDEO_CALLBACK, System.nanoTime() - callbackStartedNs)
        }
    }

    internal fun prepareVideoSurface(width: Int, height: Int): Surface? = synchronized(videoLock) {
        if (!isBroadcasting.get()) return@synchronized null
        if (hardwareEncoder !is SurfaceVideoEncoder || currentVideoWidth != width || currentVideoHeight != height)
            createVideoProducerLocked(width, height, surfaceInput = true)
        (hardwareEncoder as? SurfaceVideoEncoder)?.inputSurface
    }

    /** Called by the GPU thread before submitting a frame, using the sensor clock for pacing. */
    internal fun onSurfaceFrame(sensorTimestampNs: Long): Long? = synchronized(videoLock) {
        if (!isBroadcasting.get()) return@synchronized null
        diagnostics.metrics.count(StreamMetrics.Counter.CAMERA)
        val encoder = hardwareEncoder as? SurfaceVideoEncoder ?: return@synchronized null
        if (videoFramePacer?.shouldEncode(sensorTimestampNs) != true) {
            diagnostics.metrics.count(StreamMetrics.Counter.PACED_DROP)
            return@synchronized null
        }
        if (encoder.pendingFrames >= 6) {
            encoder.checkProgress()
            diagnostics.metrics.count(StreamMetrics.Counter.BUSY_DROP)
            return@synchronized null
        }
        val stamp = videoTimestampMapper?.map(sensorTimestampNs, System.nanoTime(), startNanoTime) ?: return@synchronized null
        if (stamp.reset) diagnostics.metrics.count(StreamMetrics.Counter.VIDEO_TIMESTAMP_RESET)
        val used = videoDemand?.isUsed() == true
        if (used && !videoWasUsed) encoder.requestKeyframe(stamp.timestampUs)
        videoWasUsed = used
        encoder.submitted()
        diagnostics.metrics.count(StreamMetrics.Counter.SUBMITTED)
        stamp.timestampUs * 1_000L
    }

    internal fun cameraFailed(error: Throwable) {
        val state = _streamState.value
        if (state is StreamState.Connecting || state is StreamState.Live)
            scope.launch { failBroadcast(error, R.string.error_video) }
    }

    private fun createVideoProducerLocked(width: Int, height: Int, surfaceInput: Boolean = false) {
        require(width > 0 && height > 0) { "Invalid video dimensions: ${width}x$height" }

        val lastAcceptedBitrate = hardwareEncoder?.bitrateBps
        runCatching { hardwareEncoder?.close() }
        hardwareEncoder = null
        runCatching { videoDemand?.close() }
        videoDemand = null
        videoWasUsed = false
        // The publisher owns the native producer. Clear only the demand here;
        // the old track is finished on its own thread after queued frames have
        // been discarded, so a resolution change cannot block CameraX.
        videoProducer = null
        currentVideoWidth = 0
        currentVideoHeight = 0

        val trackName = if (videoTrackCounter == 0) "camera" else "camera_${videoTrackCounter}"
        val generation = videoGeneration + 1
        videoGeneration = generation
        val bitrateBps = lastAcceptedBitrate ?: configuredBitrate
        Log.d(
            TAG,
            "Setting up Android hardware encoder: ${width}x${height} @ ${configuredFramerate}fps " +
                "$configuredCodec (${bitrateBps / 1000}kbps)"
        )
        val encoded: (AnnexB.AccessUnit, ByteArray, Long) -> Unit = encoded@{ unit, headers, timestampUs ->
            if (!isBroadcasting.get()) return@encoded
            val queue = videoPublishQueue ?: return@encoded
            val result = queue.offer(EncodedVideoPacket(
                generation = generation,
                trackName = trackName,
                width = width,
                height = height,
                bitrateBps = bitrateBps,
                framerate = configuredFramerate,
                codec = configuredCodec,
                accessUnit = unit,
                headers = headers,
                timestampUs = timestampUs
            ))
            diagnostics.metrics.videoQueueDepth(queue.depth())
            if (result == VideoPublishQueue.OfferResult.DROPPED || result == VideoPublishQueue.OfferResult.RESYNC) {
                diagnostics.metrics.count(StreamMetrics.Counter.VIDEO_QUEUE_DROP)
            }
            if (result == VideoPublishQueue.OfferResult.RESYNC) scope.launch {
                synchronized(videoLock) { hardwareEncoder?.requestKeyframe(timestampUs) }
            }
        }
        hardwareEncoder = if (surfaceInput) SurfaceVideoEncoder.open(
            width, height, configuredFramerate, bitrateBps, configuredCodec, diagnostics,
            onFailure = ::cameraFailed, onEncoded = encoded
        ) else HardwareVideoEncoder.open(width, height, configuredFramerate, bitrateBps, configuredCodec, diagnostics, encoded)
        videoPublishQueue?.clear()
        videoTrackCounter++
        currentVideoWidth = width
        currentVideoHeight = height
    }

    private fun startStatsMonitoring(moq: Moq) {
        statsJob?.cancel()
        statsJob = scope.launch {
            val sendRate = SendRateEstimator()
            var lastEpoch: ULong? = null
            while (isActive && isBroadcasting.get()) {
                try {
                    val epochBefore = moq.epoch()
                    val stats = moq.session.stats()
                    val sampleTimeNanos = System.nanoTime()
                    val epoch = moq.epoch()
                    val rttMs = stats.rttUs?.let { (it / 1000uL).toLong() }
                    val videoQueueDepth = videoPublishQueue?.depth() ?: 0
                    var bitrateChanged: Long? = null
                    var bitrateError: Throwable? = null
                    if (isActive && isBroadcasting.get() && moqSession === moq) {
                        synchronized(videoLock) {
                            if (isBroadcasting.get() && bitrateAdaptationEnabled && hardwareEncoder != null) {
                                videoBitrateControl?.update(
                                    capacityBps = stats.estimatedSendRateBps,
                                    queueDepth = videoQueueDepth,
                                    rttMs = rttMs
                                )?.let { rate ->
                                    runCatching { hardwareEncoder?.setBitrate(rate) }.onSuccess {
                                        bitrateChanged = rate
                                    }.onFailure {
                                        bitrateAdaptationEnabled = false
                                        Log.w(TAG, "Hardware encoder cannot change bitrate while running: ${DiagnosticRedactor.redact(it.toString())}")
                                        bitrateError = it
                                    }
                                }
                            }
                        }
                    }
                    bitrateChanged?.let { diagnostics.event("video_bitrate_changed", mapOf("bitrate_bps" to it)) }
                    bitrateError?.let { diagnostics.error("video_bitrate_change_failed", it) }
                    // The estimate is available network capacity, not media bitrate.
                    // Counter deltas include transport overhead and retransmissions.
                    // Discard a sample spanning a reconnect; its counters may belong
                    // to the old transport even if the total has not decreased.
                    val sendKbps = sendRate.sample(
                        bytesSent = stats.bytesSent.takeIf { epochBefore == epoch },
                        timeNanos = sampleTimeNanos,
                        epoch = epoch
                    )
                    val sentMb = stats.bytesSent?.let { it.toDouble() / (1024.0 * 1024.0) }

                    if (isActive && isBroadcasting.get() && moqSession === moq) {
                        if (lastEpoch != epoch) {
                            diagnostics.event("relay_epoch", mapOf("epoch" to epoch, "network" to diagnostics.networkDescription()))
                            lastEpoch = epoch
                        }
                        val encoderStatus = synchronized(videoLock) {
                            mapOf("encoder_pending" to hardwareEncoder?.pendingFrames,
                                "video_budget_bps" to hardwareEncoder?.bitrateBps,
                                "bitrate_adaptation" to bitrateAdaptationEnabled,
                                "video_used" to videoDemand?.isUsed(),
                                "video_queue_depth" to videoQueueDepth,
                                "audio_queue_depth" to (audioPublishQueue?.depth() ?: 0))
                        }
                        val frameMetrics = diagnostics.metrics.sample()
                        diagnostics.event("metrics", frameMetrics + audioHealth.sample() + encoderStatus + diagnostics.performance() + mapOf(
                            "epoch" to epoch, "rtt_ms" to rttMs, "send_kbps" to sendKbps,
                            "network_capacity_bps" to stats.estimatedSendRateBps, "transport_bytes_sent" to stats.bytesSent,
                            "transport_bytes_received" to stats.bytesReceived, "transport_bytes_lost" to stats.bytesLost,
                            "packets_sent" to stats.packetsSent, "packets_received" to stats.packetsReceived,
                            "packets_lost" to stats.packetsLost
                        ))
                        _streamState.update { current ->
                            if (current !is StreamState.Live || current.phase == RelayConnectionPhase.RECONNECTING) current else current.copy(
                                rttMs = rttMs,
                                sendRateKbps = sendKbps,
                                bytesSentMb = sentMb,
                                publishedFps = frameMetrics["published_fps"]?.toString()?.toDoubleOrNull(),
                                videoBitrateBps = encoderStatus["video_budget_bps"] as? Long,
                                epoch = epoch
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Stats collection error: ${DiagnosticRedactor.redact(e.toString())}")
                    diagnostics.error("stats_error", e)
                }
                delay(1_000)
            }
        }
    }

    fun stopBroadcast() {
        diagnostics.event("broadcast_stop_requested")
        val job = synchronized(lifecycleLock) {
            isBroadcasting.set(false)
            broadcastJob
        }

        if (job != null) {
            job.cancel(CancellationException("Broadcast stopped by user"))
        }
        // runBroadcast owns cleanup exactly once. A late service stop must not
        // schedule another cleanup that could close the next broadcast's resources.
    }

    private fun failBroadcast(error: Throwable, fallbackMessage: Int = R.string.error_audio_capture) {
        Log.e(TAG, "Broadcast pipeline failed: ${DiagnosticRedactor.redact(error.stackTraceToString())}")
        diagnostics.error("pipeline_error", error)
        _streamState.value = StreamState.Error(formatError(error, fallbackMessage))
        stopBroadcast()
    }

    private fun stopVideoPublisher() {
        val queue = videoPublishQueue
        videoPublishQueue = null
        queue?.close()
        val executor = videoPublishExecutor
        videoPublishExecutor = null
        executor?.shutdownNow()
        val future = videoPublishFuture
        videoPublishFuture = null
        runCatching { future?.get(750L, TimeUnit.MILLISECONDS) }
        executor?.shutdownNow()
    }

    private fun stopAudioPublisher() {
        val queue = audioPublishQueue
        audioPublishQueue = null
        queue?.close()
        val executor = audioPublishExecutor
        audioPublishExecutor = null
        executor?.shutdownNow()
        val future = audioPublishFuture
        audioPublishFuture = null
        runCatching { future?.get(750L, TimeUnit.MILLISECONDS) }
        executor?.shutdownNow()
    }

    /** Releases the native session and coroutine scope when the ViewModel is cleared. */
    fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            isBroadcasting.set(false)
            broadcastJob?.cancel(CancellationException("Streamer closed"))
        }
        cleanup()
        scope.cancel()
    }

    private fun cleanup() {
        isBroadcasting.set(false)
        // Release the EGL producer before its MediaCodec input Surface, including on handover.
        runCatching { beforeVideoEncoderClose() }.onFailure { diagnostics.error("encoder_surface_detach_failed", it) }
        statsJob?.cancel()
        statsJob = null
        // Stop publisher workers before closing native producers. This keeps a
        // write/close race from reaching the MoQ FFI and bounds teardown time.
        stopVideoPublisher()
        synchronized(audioLock) {
            runCatching { audioRecord?.stop() }
            audioCaptureExecutor?.shutdownNow()
            runCatching { audioCaptureFuture?.get(750L, TimeUnit.MILLISECONDS) }
            runCatching { audioCaptureExecutor?.awaitTermination(750L, TimeUnit.MILLISECONDS) }
            audioCaptureFuture = null
            audioCaptureExecutor = null
            runCatching { audioRecord?.release() }
            audioRecord = null
            stopAudioPublisher()
            _audioCaptureStatus.value = null
        }

        synchronized(videoLock) {
            runCatching { hardwareEncoder?.close() }
            hardwareEncoder = null
            runCatching { videoDemand?.close() }
            videoDemand = null
            runCatching { videoProducer?.finish() }
            runCatching { videoProducer?.close() }
            videoProducer = null
            videoFramePacer = null
            videoTimestampMapper = null
            videoGeneration = 0
            currentVideoWidth = 0
            currentVideoHeight = 0
            videoTrackCounter = 0
        }

        synchronized(streamLock) {
            runCatching { broadcastProducer?.end() }
            runCatching { broadcastProducer?.close() }
            broadcastProducer = null

            runCatching { sessionBandwidth?.close() }
            sessionBandwidth = null

            try {
                moqSession?.close()
            } catch (_: Throwable) {
            }
            moqSession = null
        }
        diagnostics.event("broadcast_cleaned_up", diagnostics.metrics.sample())
    }

    private fun formatError(error: Throwable, resource: Int): UiMessage {
        (error as? UiException)?.let { return it.uiMessage }
        if (error is MoqException) {
            if (error.isAuth) return UiMessage(R.string.error_relay_auth)
            error.protocolError?.let { protocol ->
                return UiMessage(R.string.error_relay_protocol, listOf(protocol.scope.name, protocol.kind.name, protocol.code.toString()))
            }
        }
        return if (configuredProvider == RelayProvider.CLOUDFLARE && resource == R.string.error_broadcast)
            UiMessage(R.string.error_cloudflare_connection) else UiMessage(resource)
    }
}
