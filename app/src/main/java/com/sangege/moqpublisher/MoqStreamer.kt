package com.sangege.moqpublisher

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import uniffi.moq.MoqException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.LinkedHashMap

private const val TAG = "MoqStreamer"

private data class RecoveryRequest(
    val component: RecoveryComponent,
    val error: Throwable,
    val fallbackMessage: Int
)

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data class Live(
        val rttMs: Long? = null,
        val sendRateKbps: Long? = null,
        val bytesSentMb: Double? = null,
        val publishedFps: Double? = null,
        val videoBitrateBps: Long? = null,
        val encoderName: String? = null,
        val videoQueueDepth: Int = 0,
        val videoQueueAgeMs: Long? = null,
        val packetsLost: ULong? = null,
        val epoch: ULong = 1u,
        val phase: RelayConnectionPhase = RelayConnectionPhase.CONNECTED,
        val reconnectCount: Long = 0,
        val endpointDraft: Int? = null,
        val runtimeBitrateSupport: RuntimeBitrateSupport = RuntimeBitrateSupport.UNKNOWN
    ) : StreamState
    data class Error(val message: UiMessage) : StreamState
}

/** Coordinates the MoQ session, camera frames, microphone capture and statistics. */
class MoqStreamer(private val context: Context, private val diagnostics: AppDiagnostics) {

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()
    private val _lifecycleState = MutableStateFlow(BroadcastLifecycleState())
    val lifecycleState: StateFlow<BroadcastLifecycleState> = _lifecycleState.asStateFlow()
    private val _audioCaptureStatus = MutableStateFlow<AudioCaptureStatus?>(null)
    val audioCaptureStatus: StateFlow<AudioCaptureStatus?> = _audioCaptureStatus.asStateFlow()
    private val _audioMuted = MutableStateFlow(false)
    val audioMuted: StateFlow<Boolean> = _audioMuted.asStateFlow()
    private val _audioLevel = MutableStateFlow(AudioLevel(0f, 0f, 0f, false))
    val audioLevel: StateFlow<AudioLevel> = _audioLevel.asStateFlow()
    private val healthEvaluator = StreamHealthEvaluator()
    private val deviceHealthMonitor = DeviceHealthMonitor(context)
    private val _deviceHealth = MutableStateFlow(DeviceHealthSnapshot())
    val deviceHealth: StateFlow<DeviceHealthSnapshot> = _deviceHealth.asStateFlow()
    private val _healthState = MutableStateFlow(StreamHealthSnapshot())
    val healthState: StateFlow<StreamHealthSnapshot> = _healthState.asStateFlow()
    private val alertManager = StreamAlertManager()
    private val _alertState = MutableStateFlow(StreamAlertState())
    val alertState: StateFlow<StreamAlertState> = _alertState.asStateFlow()

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

    private var videoPublishQueue: VideoPublishQueue? = null
    private var videoPublishExecutor: ExecutorService? = null
    private var videoPublishFuture: Future<*>? = null
    private var audioPublishQueue: AudioPublishQueue? = null
    private var audioPublishExecutor: ExecutorService? = null
    private var audioPublishFuture: Future<*>? = null
    private var audioCaptureExecutor: ExecutorService? = null
    private var audioCaptureFuture: Future<*>? = null
    private val audioHealth = AudioHealthMetrics()
    private val audioMute = AtomicBoolean(false)
    private val audioGainTarget = AtomicInteger(AudioSettings.DEFAULT_GAIN)
    private val recoveryCoordinator = BroadcastRecoveryCoordinator()
    private val recoveryLock = Any()
    private val pendingRecoveries = LinkedHashMap<RecoveryComponent, RecoveryRequest>()
    private var recoveryWorker: Job? = null

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
    private var configuredAudioChannel = AudioChannel.MONO
    private var configuredAudioQuality = AudioQuality.STANDARD
    private var configuredAudioGain = AudioSettings.DEFAULT_GAIN
    @Volatile
    private var configuredAudioDeviceId = DEFAULT_AUDIO_INPUT_DEVICE_ID
    private var configuredAudioEnabled = false
    private var configuredProvider = RelayProvider.MOQ_PRO
    private var configuredDraft: Int? = null
    private var audioTrackCounter = 0
    private var audioDeviceCallback: AudioDeviceCallback? = null
    internal var beforeVideoEncoderClose: () -> Unit = {}
    internal var requestCameraRecovery: () -> Unit = {}
    internal val canStart: Boolean get() = synchronized(lifecycleLock) { !closed && broadcastJob == null }

    internal fun startBroadcast(
        relayUrl: String,
        streamPath: String,
        tlsVerify: Boolean,
        enableAudio: Boolean,
        audioChannel: AudioChannel = AudioChannel.MONO,
        audioQuality: AudioQuality = AudioQuality.STANDARD,
        audioGainPercent: Int = AudioSettings.DEFAULT_GAIN,
        codec: VideoCodec = VideoCodec.H264,
        framerate: Int = 30,
        videoBitrate: VideoBitrateConfiguration,
        provider: RelayProvider = RelayProvider.MOQ_PRO,
        protocolDraft: Int? = null
    ) {
        synchronized(lifecycleLock) {
            // A cancelled job still owns its resources until cleanup completes.
            if (closed || broadcastJob != null) return

            cancelRecoveryWorker()
            recoveryCoordinator.reset()
            audioTrackCounter = 0
            diagnostics.metrics.reset()
            audioHealth.reset()
            diagnostics.event("broadcast_start_requested", mapOf(
                "relay" to RelayUrl.maskJwt(relayUrl), "path" to streamPath, "tls_verify" to tlsVerify,
                "provider" to provider, "endpoint_draft" to protocolDraft,
                "audio" to enableAudio, "codec" to codec, "fps" to framerate, "bitrate_bps" to videoBitrate.targetBps,
                "min_bitrate_bps" to videoBitrate.minimumBps, "max_bitrate_bps" to videoBitrate.maximumBps,
                "adaptive_bitrate" to videoBitrate.adaptive,
                "audio_channel" to audioChannel, "audio_gain_percent" to audioGainPercent,
                "network" to diagnostics.networkDescription()
            ))

            configuredCodec = codec
            configuredFramerate = framerate.coerceIn(1, 120)
            configuredAudioChannel = audioChannel
            configuredAudioQuality = audioQuality
            configuredAudioGain = AudioSettings.normalizeGain(audioGainPercent)
            audioGainTarget.set(configuredAudioGain)
            configuredAudioEnabled = enableAudio
            audioMute.set(false)
            _audioMuted.value = false
            configuredProvider = provider
            configuredDraft = protocolDraft
            _audioCaptureStatus.value = null
            _audioLevel.value = AudioLevel(0f, 0f, 0f, false)
            videoBitrateControl = VideoBitrateControl(
                videoBitrate, if (enableAudio) audioQuality.bitrateBps(audioChannel) else 0L
            )
            transitionLifecycle(BroadcastLifecycleEvent.StartRequested)
            _streamState.value = StreamState.Connecting
            _healthState.value = healthEvaluator.reset()
            _alertState.value = alertManager.reset()
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
                transitionLifecycle(BroadcastLifecycleEvent.Connecting)
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
                    transitionLifecycle(BroadcastLifecycleEvent.Reconnecting)
                    connection.cancelAndJoin()
                }
                reconnectBase = ((_streamState.value as? StreamState.Live)?.reconnectCount ?: reconnectBase) + 1
                cleanup()
            }
        } catch (e: TimeoutCancellationException) {
            terminalState = StreamState.Error(UiMessage(R.string.error_relay_timeout))
            _streamState.value = terminalState
            transitionLifecycle(BroadcastLifecycleEvent.Failed(terminalState.message))
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
            transitionLifecycle(BroadcastLifecycleEvent.Failed(terminalState.message))
        } finally {
            monitor?.close()
            handovers.close()
            cleanup()
            transitionLifecycle(BroadcastLifecycleEvent.CleanedUp)
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

            if (enableAudio) {
                try {
                    startAudioCapture(broadcast, bandwidth)
                } catch (error: Throwable) {
                    if (classifyRecovery(error) == RecoveryClassification.FATAL) throw error
                    scheduleMediaRecovery(RecoveryComponent.AUDIO_CAPTURE, error, R.string.error_audio_capture)
                }
            }

            currentCoroutineContext().ensureActive()
            Log.d(TAG, "Announcing broadcast route...")
            broadcast.announce(Route())

            // announce() updates the local origin. It is not a relay acceptance or decode ACK.
            Log.d(TAG, "Local broadcast advertised; waiting for media demand.")
            transitionLifecycle(BroadcastLifecycleEvent.Announced)
            transitionLifecycle(BroadcastLifecycleEvent.SubscriberDemand(false))
            diagnostics.event("broadcast_advertised", mapOf("epoch" to moq.epoch()))
            startStatsMonitoring(moq)
            launch {
                var lastEpoch = 0uL
                var lastStatus: ConnectionStatus? = null
                while (isActive) {
                    val status = moq.session.status()
                    val epoch = moq.epoch()
                    if (status != lastStatus || epoch != lastEpoch) {
                        diagnostics.event("relay_status", mapOf("status" to status, "epoch" to epoch))
                        lastStatus = status
                    }
                    when (status) {
                        ConnectionStatus.CONNECTED -> {
                            _streamState.update { current ->
                                if (current is StreamState.Live && epoch != lastEpoch)
                                    RelayConnectionPolicy.reconnected(current, epoch, reconnectBase) else current
                            }
                            if (epoch != lastEpoch) {
                                transitionLifecycle(BroadcastLifecycleEvent.Recovered)
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
                        }.also { transitionLifecycle(BroadcastLifecycleEvent.Reconnecting) }
                    }
                    delay(RelayConnectionPolicy.SESSION_STATUS_POLL_MS)
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
                    transitionLifecycle(BroadcastLifecycleEvent.SubscriberDemand(requested))
                    _streamState.update { current ->
                        if (current is StreamState.Live) RelayConnectionPolicy.mediaWritten(current, requested) else current
                    }
                }
            } catch (_: InterruptedException) {
                // Cleanup interrupts the worker after closing its queue.
            } catch (error: Throwable) {
                if (isBroadcasting.get()) scheduleMediaRecovery(RecoveryComponent.VIDEO_PUBLISH_QUEUE, error, R.string.error_video)
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
    private fun startAudioCapture(broadcast: BroadcastProducer, bandwidth: Bandwidth?) {
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
            val trackName = nextAudioTrackName()
            try {
                val (actualChannel, createdRecord) = AudioSettings.openCapture(configuredAudioChannel, ::openAudioRecord) {
                    diagnostics.error("audio_stereo_fallback", it)
                }
                record = createdRecord
                val frameBytes = AudioSettings.frameBytes(actualChannel)
                val actualBitrate = configuredAudioQuality.bitrateBps(actualChannel)
                // Account for the actual Opus channel configuration, including a mono fallback.
                synchronized(videoLock) {
                    videoBitrateControl?.audioBps = actualBitrate
                }
                Log.d(TAG, "Encoding audio with Opus (48kHz, $actualChannel)...")
                val audio = MoqAudioPublisher.open(broadcast, actualChannel, bandwidth, actualBitrate, trackName)
                producer = audio

                val activeRecord = createdRecord
                val routed = activeRecord.routedDevice
                val requestedDeviceId = configuredAudioDeviceId.takeUnless { it == DEFAULT_AUDIO_INPUT_DEVICE_ID }
                val actualDeviceId = routed.id.takeIf { it != 0 }
                val actualDeviceName = routed.productName?.toString()?.takeIf { it.isNotBlank() }
                if (requestedDeviceId != null && actualDeviceId != null && requestedDeviceId != actualDeviceId) {
                    diagnostics.event("audio_device_fallback", mapOf(
                        "requested_id" to requestedDeviceId, "actual_id" to actualDeviceId,
                        "actual_name" to actualDeviceName
                    ))
                }
                _audioCaptureStatus.value = AudioCaptureStatus(
                    configuredAudioChannel, actualChannel, configuredAudioGain, actualBitrate,
                    requestedDeviceId = requestedDeviceId,
                    actualDeviceId = actualDeviceId,
                    actualDeviceName = actualDeviceName
                )
                diagnostics.event("audio_started", mapOf(
                    "sample_rate" to sampleRate, "frame_bytes" to frameBytes,
                    "channel_requested" to configuredAudioChannel, "channel_actual" to actualChannel,
                    "gain_percent" to configuredAudioGain, "bitrate_bps" to actualBitrate, "quality" to configuredAudioQuality,
                    "track" to trackName,
                    "group_duration_ms" to MoqAudioPublisher.GROUP_DURATION_MS,
                    "audio_thread_priority" to Process.THREAD_PRIORITY_AUDIO
                ))
                audioRecord = activeRecord
                registerAudioDeviceWatcher()
                startAudioPublisher(audio)
                publisherStarted = true
                val captureExecutor = Executors.newSingleThreadExecutor { audioThread(it, "MoqAudioCapture") }
                audioCaptureExecutor = captureExecutor
                audioCaptureFuture = captureExecutor.submit {
                    val buffer = ByteArray(frameBytes)
                    val timeline = AudioTimeline()
                    val hardwareTimestamp = AudioTimestamp()
                    var framesRead = 0L
                    var appliedGain = configuredAudioGain
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
                                val requestedGain = if (audioMute.get()) 0 else audioGainTarget.get()
                                appliedGain = AudioSettings.rampGain(appliedGain, requestedGain)
                                AudioSettings.applyGain(buffer, appliedGain)
                                val stamp = timeline.next((System.nanoTime() - startNanoTime) / 1000L)
                                audioHealth.captured(buffer, actualChannel.count)
                                if ((framesRead / AudioSettings.SAMPLES_PER_FRAME) % 5L == 0L) {
                                    _audioLevel.value = audioHealth.level()
                                    _audioCaptureStatus.value = _audioCaptureStatus.value?.copy(gainPercent = appliedGain)
                                }
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
                        if (!Thread.currentThread().isInterrupted && isBroadcasting.get())
                            scheduleMediaRecovery(RecoveryComponent.AUDIO_CAPTURE, e, R.string.error_audio_capture)
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
                if (isBroadcasting.get()) scheduleMediaRecovery(RecoveryComponent.AUDIO_CAPTURE, error, R.string.error_audio_capture)
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

    private fun nextAudioTrackName(): String {
        val index = audioTrackCounter++
        return if (index == 0) "mic" else "mic_$index"
    }

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
            val preferred = AudioInputDeviceRepository(context).find(configuredAudioDeviceId)
            if (preferred != null && !record.setPreferredDevice(preferred)) {
                diagnostics.event("audio_device_preference_rejected", mapOf("device_id" to preferred.id))
            }
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

    fun setAudioInputDevice(id: Int) {
        val normalized = if (id == DEFAULT_AUDIO_INPUT_DEVICE_ID) DEFAULT_AUDIO_INPUT_DEVICE_ID else id
        configuredAudioDeviceId = normalized
        if (configuredAudioEnabled && isBroadcasting.get()) {
            val preferred = AudioInputDeviceRepository(context).find(normalized)
            audioRecord?.let { record ->
                if (preferred != null) record.setPreferredDevice(preferred)
            }
            scheduleMediaRecovery(
                RecoveryComponent.AUDIO_CAPTURE,
                IllegalStateException("Audio input device preference changed"),
                R.string.error_audio_capture
            )
        }
        diagnostics.event("audio_input_device_selected", mapOf("device_id" to normalized.takeUnless { it == DEFAULT_AUDIO_INPUT_DEVICE_ID }))
    }

    private fun registerAudioDeviceWatcher() {
        if (audioDeviceCallback != null) return
        val manager = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                diagnostics.event("audio_devices_added", mapOf("count" to addedDevices.size))
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val removed = removedDevices.map { it.id }
                if (configuredAudioDeviceId != DEFAULT_AUDIO_INPUT_DEVICE_ID && configuredAudioDeviceId in removed) {
                    configuredAudioDeviceId = DEFAULT_AUDIO_INPUT_DEVICE_ID
                    diagnostics.event("audio_input_device_removed", mapOf("device_id" to removed))
                    if (isBroadcasting.get()) scheduleMediaRecovery(
                        RecoveryComponent.AUDIO_CAPTURE,
                        IllegalStateException("Selected audio input device was removed"),
                        R.string.error_audio_capture
                    )
                } else diagnostics.event("audio_devices_removed", mapOf("count" to removedDevices.size))
            }
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        audioDeviceCallback = callback
    }

    private fun unregisterAudioDeviceWatcher() {
        audioDeviceCallback?.let { context.getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
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
            if (isBroadcasting.get()) scheduleMediaRecovery(RecoveryComponent.VIDEO_ENCODER, e, R.string.error_video)
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
        if (state is StreamState.Connecting || state is StreamState.Live) {
            if (isBroadcasting.get()) scheduleMediaRecovery(RecoveryComponent.CAMERA, error, R.string.error_video)
            else scope.launch { failBroadcast(error, R.string.error_video) }
        }
    }

    private fun scheduleMediaRecovery(component: RecoveryComponent, error: Throwable, fallbackMessage: Int) {
        if (closed || !isBroadcasting.get()) return
        val request = RecoveryRequest(component, error, fallbackMessage)
        val started = synchronized(recoveryLock) {
            pendingRecoveries[component] = request
            if (recoveryWorker?.isActive == true) false else {
                recoveryWorker = scope.launch { runRecoveryWorker() }
                true
            }
        }
        diagnostics.event(
            if (started) "media_recovery_queued" else "media_recovery_coalesced",
            mapOf("component" to component, "error" to DiagnosticRedactor.redact(error.javaClass.simpleName))
        )
    }

    private suspend fun runRecoveryWorker() {
        try {
            while (currentCoroutineContext().isActive && isBroadcasting.get()) {
                val request = synchronized(recoveryLock) {
                    pendingRecoveries.entries.firstOrNull()?.also { pendingRecoveries.remove(it.key) }?.value
                } ?: return
                val classification = classifyRecovery(request.error)
                val attempt = recoveryCoordinator.begin(request.component, classification)
                diagnostics.event("media_recovery_attempt", mapOf(
                    "component" to request.component, "attempt" to attempt.attempt,
                    "delay_ms" to attempt.delayMs, "allowed" to attempt.allowed,
                    "classification" to attempt.classification
                ))
                if (!attempt.allowed) {
                    diagnostics.error("media_recovery_exhausted", request.error)
                    failBroadcast(request.error, request.fallbackMessage)
                    return
                }

                transitionLifecycle(BroadcastLifecycleEvent.RecoveryStarted)
                _healthState.value = healthEvaluator.markRecovering()
                try {
                    delay(attempt.delayMs)
                    currentCoroutineContext().ensureActive()
                    recoverComponent(request.component)
                    recoveryCoordinator.succeeded(request.component)
                    transitionLifecycle(BroadcastLifecycleEvent.RecoveryCompleted(videoDemand?.isUsed() == true))
                    diagnostics.event("media_recovery_succeeded", mapOf(
                        "component" to request.component, "attempt" to attempt.attempt
                    ))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    diagnostics.error("media_recovery_failed", error)
                    synchronized(recoveryLock) {
                        pendingRecoveries[request.component] = request.copy(error = error)
                    }
                }
            }
        } finally {
            synchronized(recoveryLock) {
                recoveryWorker = null
                if (pendingRecoveries.isNotEmpty() && isBroadcasting.get() && !closed) {
                    recoveryWorker = scope.launch { runRecoveryWorker() }
                }
            }
        }
    }

    private fun recoverComponent(component: RecoveryComponent) {
        when (component) {
            RecoveryComponent.CAMERA -> {
                resetVideoPipeline("camera")
                requestCameraRecovery()
            }
            RecoveryComponent.VIDEO_ENCODER -> resetVideoPipeline("video_encoder")
            RecoveryComponent.VIDEO_PUBLISH_QUEUE -> {
                val broadcast = synchronized(streamLock) { broadcastProducer }
                    ?: error("Broadcast is unavailable for video publisher recovery")
                stopVideoPublisher()
                resetVideoPipeline("video_publish_queue")
                if (isBroadcasting.get()) startVideoPublisher(broadcast)
            }
            RecoveryComponent.AUDIO_CAPTURE -> recoverAudioCapture()
        }
    }

    private fun recoverAudioCapture() {
        val broadcast = synchronized(streamLock) { broadcastProducer }
            ?: error("Broadcast is unavailable for audio recovery")
        val bandwidth = synchronized(streamLock) { sessionBandwidth }
        synchronized(audioLock) {
            stopAudioCaptureLocked()
            if (isBroadcasting.get()) startAudioCapture(broadcast, bandwidth)
        }
    }

    private fun resetVideoPipeline(reason: String) {
        synchronized(videoLock) {
            if (!isBroadcasting.get()) return
            runCatching { beforeVideoEncoderClose() }
                .onFailure { diagnostics.error("encoder_surface_detach_failed", it) }
            runCatching { hardwareEncoder?.close() }
            hardwareEncoder = null
            runCatching { videoDemand?.close() }
            videoDemand = null
            videoProducer = null
            videoWasUsed = false
            videoGeneration++
            currentVideoWidth = 0
            currentVideoHeight = 0
            videoPublishQueue?.waitForKeyframe()
            diagnostics.event("video_pipeline_reset", mapOf("reason" to reason, "generation" to videoGeneration))
        }
    }

    private fun classifyRecovery(error: Throwable): RecoveryClassification = when {
        error is CancellationException -> RecoveryClassification.FATAL
        error is MoqException && error.isAuth -> RecoveryClassification.FATAL
        error is UiException && error.uiMessage.resource in setOf(
            R.string.error_encoder_unsupported,
            R.string.error_microphone_permission
        ) -> RecoveryClassification.FATAL
        else -> RecoveryClassification.TRANSIENT
    }

    private fun createVideoProducerLocked(width: Int, height: Int, surfaceInput: Boolean = false) {
        require(width > 0 && height > 0) { "Invalid video dimensions: ${width}x$height" }

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
        val bitrateControl = checkNotNull(videoBitrateControl)
        val bitrateBps = bitrateControl.currentBps
        Log.d(
            TAG,
            "Setting up Android hardware encoder: ${width}x${height} @ ${configuredFramerate}fps " +
                "$configuredCodec (${bitrateBps / 1000}kbps)"
        )
        val encoded: (AnnexB.AccessUnit, ByteArray, Long) -> Unit = encoded@{ unit, headers, timestampUs ->
            if (!isBroadcasting.get()) return@encoded
            if (generation != videoGeneration) return@encoded
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
        bitrateControl.encoderOpened()
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
                    val videoQueueAgeMs = videoPublishQueue?.mediaAgeMs()
                    val deviceHealth = deviceHealthMonitor.sample()
                    _deviceHealth.value = deviceHealth
                    var bitrateChanged: Long? = null
                    var bitrateError: Throwable? = null
                    if (isActive && isBroadcasting.get() && moqSession === moq) {
                        synchronized(videoLock) {
                            val encoder = hardwareEncoder
                            if (isBroadcasting.get() && encoder != null) {
                                runCatching {
                                    videoBitrateControl?.update(
                                        capacityBps = stats.estimatedSendRateBps,
                                        queueDepth = videoQueueDepth,
                                        queueAgeMs = videoQueueAgeMs,
                                        rttMs = rttMs,
                                        apply = encoder::setBitrate
                                    )
                                }.onSuccess { bitrateChanged = it }.onFailure { bitrateError = it }
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
                                "encoder_name" to hardwareEncoder?.name,
                                "video_budget_bps" to hardwareEncoder?.bitrateBps,
                                "bitrate_adaptation" to videoBitrateControl?.settings?.adaptive,
                                "runtime_bitrate" to videoBitrateControl?.runtimeSupport,
                                "min_bitrate_bps" to videoBitrateControl?.settings?.minimumBps,
                                "target_bitrate_bps" to videoBitrateControl?.settings?.targetBps,
                                "max_bitrate_bps" to videoBitrateControl?.settings?.maximumBps,
                                "video_used" to videoDemand?.isUsed(),
                                "video_queue_depth" to videoQueueDepth,
                                "audio_queue_depth" to (audioPublishQueue?.depth() ?: 0))
                        }
                        val frameMetrics = diagnostics.metrics.sample()
                        val audioMetrics = audioHealth.sample()
                        val health = healthEvaluator.evaluate(StreamHealthInput(
                            active = true,
                            relayPhase = (_streamState.value as? StreamState.Live)?.phase,
                            videoDemanded = encoderStatus["video_used"] == true,
                            cameraAgeMs = metricLong(frameMetrics["last_camera_age_ms"]),
                            encoderAgeMs = metricLong(frameMetrics["last_encoded_age_ms"]),
                            publishedAgeMs = metricLong(frameMetrics["last_video_age_ms"]),
                            audioExpected = configuredAudioEnabled,
                            audioAgeMs = metricLong(frameMetrics["last_audio_age_ms"] ?: audioMetrics["audio_capture_interval_max_ms"]),
                            targetFps = configuredFramerate,
                            publishedFps = frameMetrics["published_fps"]?.toString()?.toDoubleOrNull(),
                            queueDepth = videoQueueDepth,
                            queueMediaAgeMs = videoQueueAgeMs,
                            deviceHealthIssues = deviceHealth.issues
                        ))
                        val previousHealth = _healthState.value
                        if (health.level != previousHealth.level || health.reasons != previousHealth.reasons) {
                            _healthState.value = health
                        }
                        val alerts = alertManager.update(health, System.currentTimeMillis())
                        val previousAlerts = _alertState.value
                        if (alerts.active.map { it.type } != previousAlerts.active.map { it.type } ||
                            alerts.history.size != previousAlerts.history.size) {
                            _alertState.value = alerts
                        }
                        if (health.level != previousHealth.level || health.reasons != previousHealth.reasons) {
                            diagnostics.event("stream_health", mapOf(
                                "level" to health.level,
                                "reasons" to health.reasons.map { it.name }
                            ))
                        }
                        if (alerts.active != previousAlerts.active) {
                            diagnostics.event("stream_alerts", mapOf(
                                "active" to alerts.active.map { it.type.name },
                                "history_size" to alerts.history.size
                            ))
                        }
                        diagnostics.event("metrics", frameMetrics + audioMetrics + encoderStatus + diagnostics.performance() + mapOf(
                            "health" to health.level,
                            "health_reasons" to health.reasons.map { it.name },
                            "video_queue_media_age_ms" to videoQueueAgeMs,
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
                                runtimeBitrateSupport = encoderStatus["runtime_bitrate"] as? RuntimeBitrateSupport
                                    ?: RuntimeBitrateSupport.UNKNOWN,
                                encoderName = encoderStatus["encoder_name"] as? String,
                                videoQueueDepth = videoQueueDepth,
                                videoQueueAgeMs = videoQueueAgeMs,
                                packetsLost = stats.packetsLost,
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
        transitionLifecycle(BroadcastLifecycleEvent.StopRequested)
        cancelRecoveryWorker()
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

    fun setAudioMuted(muted: Boolean) {
        if (!configuredAudioEnabled || !isBroadcasting.get()) return
        audioMute.set(muted)
        _audioMuted.value = muted
        diagnostics.event("audio_mute_changed", mapOf("muted" to muted))
    }

    fun setAudioGain(percent: Int) {
        val normalized = AudioSettings.normalizeGain(percent)
        audioGainTarget.set(normalized)
        if (!isBroadcasting.get()) configuredAudioGain = normalized
        if (configuredAudioEnabled && isBroadcasting.get()) {
            diagnostics.event("audio_gain_target_changed", mapOf("gain_percent" to normalized, "ramp_step_percent" to AudioSettings.GAIN_RAMP_STEP_PERCENT))
        }
    }

    internal suspend fun changeVideoBitrate(generation: Long, settings: VideoBitrateConfiguration): Boolean =
        changeVideoBitrate(generation) { settings }

    internal suspend fun lowerVideoBitrateForDeviceHealth(generation: Long): Boolean = changeVideoBitrate(generation) { control ->
        if (deviceHealth.value.recommendation != DeviceQualityRecommendation.REDUCE_BITRATE) return@changeVideoBitrate null
        val settings = control.settings
        val current = minOf(control.currentBps, settings.targetBps)
        val reduced = (current * 75 / 100).coerceAtLeast(settings.minimumBps)
        settings.copy(targetBps = reduced, maximumBps = reduced).takeIf { reduced < current }
    }

    private suspend fun changeVideoBitrate(
        generation: Long, settingsFor: (VideoBitrateControl) -> VideoBitrateConfiguration?
    ): Boolean = withContext(Dispatchers.IO) {
        val applied = synchronized(videoLock) {
            val state = lifecycleState.value
            if (state.generation != generation || !streamState.value.isActive() ||
                state.phase in setOf(BroadcastPhase.STOPPING, BroadcastPhase.FAILED, BroadcastPhase.IDLE)) return@synchronized null
            val control = videoBitrateControl ?: return@synchronized null
            val next = settingsFor(control) ?: return@synchronized null
            val encoder = hardwareEncoder ?: throw UiException(UiMessage(R.string.error_bitrate_encoder_not_ready))
            control.changeSettings(next, encoder::setBitrate)
            next
        }
        if (applied != null) diagnostics.event("video_bitrate_settings_applied", mapOf(
            "min_bitrate_bps" to applied.minimumBps, "target_bitrate_bps" to applied.targetBps,
            "max_bitrate_bps" to applied.maximumBps, "adaptive_bitrate" to applied.adaptive
        ))
        applied != null
    }

    private fun failBroadcast(error: Throwable, fallbackMessage: Int = R.string.error_audio_capture) {
        Log.e(TAG, "Broadcast pipeline failed: ${DiagnosticRedactor.redact(error.stackTraceToString())}")
        diagnostics.error("pipeline_error", error)
        val message = formatError(error, fallbackMessage)
        _streamState.value = StreamState.Error(message)
        transitionLifecycle(BroadcastLifecycleEvent.Failed(message))
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

    private fun stopAudioCaptureLocked() {
        unregisterAudioDeviceWatcher()
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
        _audioLevel.value = AudioLevel(0f, 0f, 0f, false)
    }

    private fun cancelRecoveryWorker() {
        synchronized(recoveryLock) {
            pendingRecoveries.clear()
            recoveryWorker?.cancel()
            recoveryWorker = null
            recoveryCoordinator.reset()
        }
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
        cancelRecoveryWorker()
        // Release the EGL producer before its MediaCodec input Surface, including on handover.
        runCatching { beforeVideoEncoderClose() }.onFailure { diagnostics.error("encoder_surface_detach_failed", it) }
        statsJob?.cancel()
        statsJob = null
        // Stop publisher workers before closing native producers. This keeps a
        // write/close race from reaching the MoQ FFI and bounds teardown time.
        stopVideoPublisher()
        synchronized(audioLock) {
            stopAudioCaptureLocked()
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

    private fun metricLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        else -> value?.toString()?.toDoubleOrNull()?.toLong()
    }

    private fun transitionLifecycle(event: BroadcastLifecycleEvent) {
        val previous = _lifecycleState.value
        val next = BroadcastLifecyclePolicy.reduce(previous, event)
        if (next != previous) {
            _lifecycleState.value = next
            diagnostics.event("broadcast_phase", mapOf("phase" to next.phase, "generation" to next.generation))
        }
    }
}
