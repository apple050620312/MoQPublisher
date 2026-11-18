package com.sangege.moqpublisher

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Application-owned session; ViewModels observe it and never close an active broadcast. */
internal class BroadcastSession(private val application: MoqApplication) {
    private val prefs = application.getSharedPreferences("moq_stream_prefs", Context.MODE_PRIVATE)
    val diagnostics = AppDiagnostics(application)
    val streamer = MoqStreamer(application, diagnostics)
    val verifier = SubscriberVerifier(diagnostics)
    val remote = RemoteControlServer(application, diagnostics)
    val controls = CameraControlHandle { ev -> prefs.edit { putFloat("camera_exposure_ev", ev) } }.apply {
        restoreExposure(prefs.getFloat("camera_exposure_ev", 0f))
    }
    val cameraError = MutableStateFlow<UiMessage?>(null)
    val camera = CameraCaptureEngine(application, controls, streamer, diagnostics) { error ->
        cameraError.value = UiMessage(R.string.error_camera)
        streamer.cameraFailed(error)
    }
    val overlay = StreamOverlay(application, prefs, diagnostics, camera::updateOverlay)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var pendingStart: BroadcastConfiguration? = null
        private set
    @Volatile var generation: Long = 0L
        private set
    private var pendingVideoMode: LiveVideoModeChange? = null
    private val mutableChangingVideoMode = MutableStateFlow(false)
    val changingVideoMode = mutableChangingVideoMode.asStateFlow()
    private var screenOnly = false
    private var subscription: BroadcastSubscription? = null
    private val mutablePreferScreen = MutableStateFlow(prefs.getBoolean("prefer_screen_source", false))
    val preferScreen = mutablePreferScreen.asStateFlow()
    val screenCapture = ScreenCaptureEngine(application, camera, diagnostics) { error ->
        if (error != null) cameraError.value = error
        if (screenOnly) stop() else updateCameraSource()
    }
    val screenCaptureState = screenCapture.state

    init {
        camera.onOverlayError = overlay::renderFailed
        streamer.beforeVideoEncoderClose = camera::detachEncoder
        streamer.requestCameraRecovery = camera::recover
        var previousConnection: Pair<ULong, Long>? = null
        var previousVideoGeneration: Int? = null
        scope.launch {
            streamer.streamState.collect { state ->
                val active = state.isActive()
                if (!active) {
                    verifier.reset()
                    remote.revoke()
                    subscription = null
                    screenCapture.stopForBroadcastEnd()
                    screenOnly = false
                }
                if (state is StreamState.Live) {
                    val connection = state.epoch to state.reconnectCount
                    if (!state.canVerifyRemoteMedia() ||
                        (previousConnection != null && previousConnection != connection) ||
                        (previousVideoGeneration != null && previousVideoGeneration != state.videoTrackGeneration)) verifier.reset()
                    previousConnection = connection
                    previousVideoGeneration = state.videoTrackGeneration
                } else if (!active) {
                    previousConnection = null
                    previousVideoGeneration = null
                }
                application.core.setStreaming(active)
                overlay.setCapturing(active)
                updateCameraSource(active)
            }
        }
    }

    fun configureCamera(front: Boolean, mode: VideoMode) {
        if (pendingVideoMode == null) camera.configure(front, mode)
    }

    suspend fun changeVideoMode(front: Boolean, previous: VideoMode, requested: VideoMode): Boolean {
        val state = streamer.streamState.value
        if (screenCaptureState.value.busy || pendingVideoMode != null ||
            !(state.canVerifyRemoteMedia() || (state as? StreamState.Live)?.recordingOnly == true)) return false
        val change = LiveVideoModeChange(
            rebuildEncoder = requested.camera.direction != previous.camera.direction ||
                requested.camera.mirrorFront != previous.camera.mirrorFront
        )
        pendingVideoMode = change
        mutableChangingVideoMode.value = true
        var applied = false
        try {
            diagnostics.event("live_video_mode_requested", mapOf("size" to requested.size, "fps" to requested.fps))
            camera.configure(front, requested, change)
            applied = withTimeoutOrNull(8_000L) {
                change.result.first { it != LiveVideoModeChange.Result.PENDING }
            } == LiveVideoModeChange.Result.APPLIED
            return applied
        } finally {
            if (!applied) {
                change.finish(false)
                camera.configure(front, previous)
            }
            pendingVideoMode = null
            mutableChangingVideoMode.value = false
            diagnostics.event("live_video_mode_result", mapOf("applied" to applied, "size" to requested.size, "fps" to requested.fps))
        }
    }

    fun setUiActive(active: Boolean) {
        camera.setUiActive(active)
        if (!active && !prefs.getBoolean("background_streaming", true)) stop()
    }

    fun setPreferScreen(enabled: Boolean) {
        if (streamer.streamState.value.isActive() || pendingStart != null) return
        mutablePreferScreen.value = enabled
        prefs.edit { putBoolean("prefer_screen_source", enabled) }
        updateCameraSource()
    }

    private fun updateCameraSource(active: Boolean = streamer.streamState.value.isActive()) {
        val screen = if (active) screenOnly || screenCaptureState.value.busy else preferScreen.value
        streamer.screenVideoSource = screen
        camera.setScreenSource(screen)
        camera.setStreaming(active && !screen)
    }

    fun canSwitchToScreen(expectedGeneration: Long): Boolean =
        expectedGeneration == generation && !screenOnly && streamer.streamState.value.isActive() &&
            !screenCaptureState.value.busy && pendingVideoMode == null

    fun requestScreenCapture(expectedGeneration: Long, request: ScreenCaptureRequest): Boolean {
        if (!canSwitchToScreen(expectedGeneration)) return false
        return runCatching {
            BroadcastService.requestScreenCapture(application, expectedGeneration, request)
        }.onFailure { diagnostics.error("screen_capture_service_request_failed", it) }.isSuccess
    }

    fun startScreenCapture(request: ScreenCaptureRequest): Boolean {
        val started = screenCapture.start(request) != null
        updateCameraSource()
        return started
    }

    fun stopScreenCapture() = screenCapture.stop()

    fun enableRemote(access: RemoteAccess, expectedGeneration: Long) {
        if (expectedGeneration != generation || streamer.streamState.value !is StreamState.Live) return
        remote.start(access) { command, kbps, granted ->
            withContext(Dispatchers.Main.immediate) {
                fun authorized() = expectedGeneration == generation && granted() &&
                    streamer.lifecycleState.value.phase != BroadcastPhase.STOPPING &&
                    streamer.streamState.value.isActive()

                if (!authorized()) return@withContext remoteReply("inactive")
                when (command) {
                    RemoteCommand.STATUS -> remoteStatus()
                    RemoteCommand.MUTE, RemoteCommand.UNMUTE -> {
                        if (streamer.audioCaptureStatus.value == null) remoteReply("audio_unavailable")
                        else {
                            streamer.setAudioMuted(command == RemoteCommand.MUTE)
                            remoteReply()
                        }
                    }
                    RemoteCommand.BITRATE -> {
                        val accepted = try {
                            streamer.changeRemoteVideoBitrate(streamer.lifecycleState.value.generation, checkNotNull(kbps) * 1_000L, ::authorized)
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            diagnostics.error("remote_bitrate_failed", error)
                            false
                        }
                        remoteReply(if (accepted) null else "bitrate_unavailable")
                    }
                    RemoteCommand.VERIFY -> {
                        val request = subscription
                        if (request == null || !streamer.streamState.value.canVerifyRemoteMedia()) {
                            remoteReply("verification_unavailable")
                        } else {
                            if (verifier.state.value !is SubscriberVerification.Running) request.verify(verifier)
                            remoteReply()
                        }
                    }
                    RemoteCommand.STOP -> RemoteResponse(remoteReply().payload) {
                        withContext(Dispatchers.Main.immediate) { if (authorized()) stopOwned(expectedGeneration) }
                    }
                }
            }
        }
    }

    private fun remoteStatus(): RemoteResponse {
        val live = streamer.streamState.value as? StreamState.Live
        val device = streamer.deviceHealth.value
        val result = remoteReply()
        result.payload.apply {
            put("phase", streamer.lifecycleState.value.phase.name)
            put("relay_phase", live?.phase?.name.takeUnless { live?.recordingOnly == true } ?: JSONObject.NULL)
            put("elapsed_seconds", live?.elapsedSeconds ?: JSONObject.NULL)
            put("fps", live?.publishedFps ?: JSONObject.NULL)
            put("bitrate_bps", live?.videoBitrateBps ?: JSONObject.NULL)
            put("rtt_ms", live?.rttMs ?: JSONObject.NULL)
            put("battery_percent", device.batteryPercent ?: JSONObject.NULL)
            put("thermal_status", device.thermalStatus ?: JSONObject.NULL)
            put("muted", streamer.audioMuted.value)
            put("verification", verifier.state.value.javaClass.simpleName)
            put("alerts", JSONArray(streamer.alertState.value.history.takeLast(10).map { alert ->
                JSONObject().put("type", alert.type.name).put("active", alert.active).put("at_ms", alert.lastSeenMs)
            }))
        }
        return result
    }

    fun requestStart(config: BroadcastConfiguration) {
        if (streamer.streamState.value is StreamState.Live || streamer.streamState.value is StreamState.Connecting) return
        if (pendingStart != null) return
        generation++
        pendingStart = config
        try { BroadcastService.start(application) }
        catch (error: Throwable) { pendingStart = null; throw error }
    }

    fun startPending(): BroadcastConfiguration? {
        val config = pendingStart ?: return null
        pendingStart = null
        cameraError.value = null
        val request = config.screen
        val projection = if (request != null) {
            screenCapture.start(request) ?: throw UiException(UiMessage(R.string.screen_capture_start_failed))
        } else null
        screenOnly = projection != null
        subscription = config.subscription
        streamer.startBroadcast(
            config.relayUrl, config.streamPath, config.tlsVerify, config.enableAudio,
            config.audioChannel, config.audioQuality, config.audioGainPercent,
            config.codec, config.framerate, config.videoBitrate, config.recording,
            config.provider, config.protocolDraft, screenOnly,
            projection.takeIf { config.capturePlaybackAudio }
        )
        return config
    }

    fun stop() {
        pendingStart = null
        remote.revoke()
        subscription = null
        pendingVideoMode?.finish(false)
        screenCapture.stopForBroadcastEnd()
        verifier.reset()
        streamer.stopBroadcast()
    }

    fun stopOwned(expectedGeneration: Long): Boolean {
        if (expectedGeneration != generation) return false
        stop()
        return true
    }
}
