package com.sangege.moqpublisher

import android.content.Context
import android.util.Size
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Application-owned session; ViewModels observe it and never close an active broadcast. */
internal class BroadcastSession(private val application: MoqApplication) {
    private val prefs = application.getSharedPreferences("moq_stream_prefs", Context.MODE_PRIVATE)
    val diagnostics = AppDiagnostics(application)
    val streamer = MoqStreamer(application, diagnostics)
    val verifier = SubscriberVerifier(diagnostics)
    val controls = CameraControlHandle { ev -> prefs.edit { putFloat("camera_exposure_ev", ev) } }.apply {
        restoreExposure(prefs.getFloat("camera_exposure_ev", 0f))
    }
    val cameraError = MutableStateFlow<UiMessage?>(null)
    val camera = CameraCaptureEngine(application, controls, streamer, diagnostics) { error ->
        cameraError.value = UiMessage(R.string.error_camera)
        streamer.cameraFailed(error)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var pendingStart: BroadcastConfiguration? = null
        private set
    var generation: Long = 0L
        private set

    init {
        streamer.beforeVideoEncoderClose = camera::detachEncoder
        streamer.requestCameraRecovery = camera::recover
        var previousConnection: Pair<ULong, Long>? = null
        scope.launch {
            streamer.streamState.collect { state ->
                val active = state.isActive()
                if (!active) verifier.reset()
                if (state is StreamState.Live) {
                    val connection = state.epoch to state.reconnectCount
                    if (state.phase == RelayConnectionPhase.RECONNECTING ||
                        (previousConnection != null && previousConnection != connection)) verifier.reset()
                    previousConnection = connection
                } else if (!active) previousConnection = null
                application.core.setStreaming(active)
                camera.setStreaming(active)
            }
        }
    }

    fun configureCamera(front: Boolean, size: Size, fps: Int) = camera.configure(front, size, fps)

    fun setUiActive(active: Boolean) {
        camera.setUiActive(active)
        if (!active && !prefs.getBoolean("background_streaming", true)) stop()
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
        streamer.startBroadcast(
            config.relayUrl, config.streamPath, config.tlsVerify, config.enableAudio,
            config.audioChannel, config.audioQuality, config.audioGainPercent,
            config.codec, config.framerate, config.bitrateBps, config.adaptiveBitrate,
            config.provider, config.protocolDraft
        )
        return config
    }

    fun stop() { pendingStart = null; verifier.reset(); streamer.stopBroadcast() }

    fun stopOwned(expectedGeneration: Long): Boolean {
        if (expectedGeneration != generation) return false
        stop()
        return true
    }
}
