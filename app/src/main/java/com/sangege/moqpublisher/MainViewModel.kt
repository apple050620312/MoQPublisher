package com.sangege.moqpublisher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageProxy
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.moq.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PREFS_NAME = "moq_stream_prefs"
private const val KEY_RELAY_URL = "relay_url"
private const val KEY_STREAM_PATH = "stream_path"
private const val KEY_TLS_VERIFY = "tls_verify"
private const val KEY_ENABLE_AUDIO = "enable_audio"
private const val KEY_USE_FRONT_CAMERA = "use_front_camera"
private const val KEY_VIDEO_CODEC = "video_codec"
private const val KEY_VIDEO_WIDTH = "video_width"
private const val KEY_VIDEO_HEIGHT = "video_height"
private const val KEY_VIDEO_FPS = "video_fps"
private const val KEY_LEGACY_VIDEO_BITRATE = "video_bitrate_mbps"
private const val KEY_VIDEO_BITRATE = "video_bitrate_kbps"
private const val KEY_ADAPTIVE_BITRATE = "adaptive_bitrate"
private const val KEY_AUDIO_CHANNEL = "audio_channel"
private const val KEY_AUDIO_GAIN = "audio_gain_percent"
private const val KEY_AUDIO_QUALITY = "audio_quality"
private const val KEY_LOCK_LANDSCAPE = "lock_landscape"
private const val KEY_BACKGROUND_STREAMING = "background_streaming"
private const val KEY_LANGUAGE = "app_language"
private const val KEY_THEME = "app_theme"
private const val KEY_SHOW_CAMERA_DETAILS = "show_camera_details"

private const val DEFAULT_RELAY_URL = "https://cdn.moq.pro/placeholder"
private const val DEFAULT_STREAM_PATH = "live/camera"
private const val DEFAULT_VIDEO_WIDTH = 1280
private const val DEFAULT_VIDEO_HEIGHT = 720
private const val DEFAULT_VIDEO_FPS = 30
private val SUPPORTED_FPS = setOf(15, 24, 30, 60)
private val RELAY_SCHEMES = setOf("http", "https", "ws", "wss")

class MainViewModel internal constructor(
    application: Application, private val prefs: SharedPreferences,
    tokenStore: ConnectionTokenStore = KeystoreConnectionTokenStore(application)
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val session = (application as MoqApplication).session
    private val diagnostics = session.diagnostics
    private val core = (application as MoqApplication).core
    val coreState = core.state
    val cameraControls = session.controls
    val cameraControlState: StateFlow<CameraControlState> = cameraControls.state
    val streamer = session.streamer
    internal val cameraEngine = session.camera
    val streamState: StateFlow<StreamState> = streamer.streamState

    private val _configurationError = MutableStateFlow<UiMessage?>(null)
    val configurationError: StateFlow<UiMessage?> = _configurationError.asStateFlow()
    private val relayProfiles = RelayProfileRepository(prefs, tokenStore)
    private var tokenReadFailed = false
    private val _relayProfile = MutableStateFlow(runCatching { relayProfiles.load() }.getOrElse {
        _configurationError.value = UiMessage(R.string.error_token_storage)
        tokenReadFailed = true
        RelayProfile()
    })
    val relayProfile = _relayProfile.asStateFlow()
    val subscriberVerification = session.verifier.state

    private val _language = MutableStateFlow(AppLanguage.fromPreference(prefs.getString(KEY_LANGUAGE, null)))
    val language: StateFlow<AppLanguage> = _language.asStateFlow()
    private val _theme = MutableStateFlow(AppTheme.fromPreference(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()
    private val _showCameraDetails = MutableStateFlow(prefs.getBoolean(KEY_SHOW_CAMERA_DETAILS, false))
    val showCameraDetails: StateFlow<Boolean> = _showCameraDetails.asStateFlow()
    private val _audioChannel = MutableStateFlow(AudioChannel.fromPreference(prefs.getString(KEY_AUDIO_CHANNEL, null)))
    val audioChannel: StateFlow<AudioChannel> = _audioChannel.asStateFlow()
    private val _audioGain = MutableStateFlow(AudioSettings.normalizeGain(prefs.getInt(KEY_AUDIO_GAIN, AudioSettings.DEFAULT_GAIN)))
    val audioGain: StateFlow<Int> = _audioGain.asStateFlow()
    private val _audioQuality = MutableStateFlow(AudioQuality.fromPreference(prefs.getString(KEY_AUDIO_QUALITY, null)))
    val audioQuality = _audioQuality.asStateFlow()
    private val _lockLandscape = MutableStateFlow(prefs.getBoolean(KEY_LOCK_LANDSCAPE, false))
    val lockLandscape = _lockLandscape.asStateFlow()
    private val _backgroundStreaming = MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND_STREAMING, true))
    val backgroundStreaming = _backgroundStreaming.asStateFlow()
    val audioCaptureStatus: StateFlow<AudioCaptureStatus?> = streamer.audioCaptureStatus

    private val _relayUrl = MutableStateFlow(
        prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
    )
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    private val _authToken = MutableStateFlow(
        runCatching { relayProfiles.moqProToken() }.getOrElse {
            _configurationError.value = UiMessage(R.string.error_token_storage)
            tokenReadFailed = true
            ""
        }
    )
    val authToken: StateFlow<String> = _authToken.asStateFlow()

    private val _streamPath = MutableStateFlow(
        prefs.getString(KEY_STREAM_PATH, DEFAULT_STREAM_PATH) ?: DEFAULT_STREAM_PATH
    )
    val streamPath: StateFlow<String> = _streamPath.asStateFlow()

    private val _tlsVerify = MutableStateFlow(
        prefs.getBoolean(KEY_TLS_VERIFY, true)
    )
    val tlsVerify: StateFlow<Boolean> = _tlsVerify.asStateFlow()

    private val _enableAudio = MutableStateFlow(
        prefs.getBoolean(KEY_ENABLE_AUDIO, true)
    )
    val enableAudio: StateFlow<Boolean> = _enableAudio.asStateFlow()

    private val _useFrontCamera = MutableStateFlow(
        prefs.getBoolean(KEY_USE_FRONT_CAMERA, false)
    )
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera.asStateFlow()

    // Video Options
    private val _videoCodec = MutableStateFlow(
        prefs.getString(KEY_VIDEO_CODEC, "H264")?.uppercase()?.takeIf { it == "H264" || it == "H265" } ?: "H264"
    )
    val videoCodec: StateFlow<String> = _videoCodec.asStateFlow()

    private val _videoResolution = MutableStateFlow(
        Size(
            prefs.getInt(KEY_VIDEO_WIDTH, DEFAULT_VIDEO_WIDTH).coerceAtLeast(2),
            prefs.getInt(KEY_VIDEO_HEIGHT, DEFAULT_VIDEO_HEIGHT).coerceAtLeast(2)
        )
    )
    val videoResolution: StateFlow<Size> = _videoResolution.asStateFlow()

    private val _videoFps = MutableStateFlow(
        prefs.getInt(KEY_VIDEO_FPS, DEFAULT_VIDEO_FPS).takeIf { it in SUPPORTED_FPS } ?: DEFAULT_VIDEO_FPS
    )
    val videoFps: StateFlow<Int> = _videoFps.asStateFlow()

    private val _videoBitrateKbps = MutableStateFlow(
        prefs.getString(KEY_VIDEO_BITRATE, null) ?: VideoBitrateSettings.migrateMbps(prefs.getString(KEY_LEGACY_VIDEO_BITRATE, null))
            .also { migrated -> prefs.edit { putString(KEY_VIDEO_BITRATE, migrated) } }
    )
    val videoBitrateKbps: StateFlow<String> = _videoBitrateKbps.asStateFlow()
    private val _adaptiveBitrate = MutableStateFlow(prefs.getBoolean(KEY_ADAPTIVE_BITRATE, true))
    val adaptiveBitrate: StateFlow<Boolean> = _adaptiveBitrate.asStateFlow()

    val cameraCaptureStatus = session.camera.captureStatus

    private val _availableResolutions = MutableStateFlow<List<Size>>(emptyList())
    val availableResolutions: StateFlow<List<Size>> = _availableResolutions.asStateFlow()

    init {
        refreshAvailableResolutions(_useFrontCamera.value)
        syncCamera()
        viewModelScope.launch { session.cameraError.collect { if (it != null) _configurationError.value = it } }
    }

    fun checkCoreUpdates() = core.checkForUpdates()

    fun setRelayUrl(url: String) {
        _relayUrl.value = url
        clearConfigurationError()
        prefs.edit { putString(KEY_RELAY_URL, url) }
    }

    fun setAuthToken(token: String) {
        if (streamState.value.isActive()) return
        runCatching { relayProfiles.saveMoqProToken(token) }.onSuccess {
            _authToken.value = token
            clearConfigurationError()
            if (tokenReadFailed) reloadCredentials()
        }.onFailure { _configurationError.value = UiMessage(R.string.error_token_storage) }
    }

    fun setRelayProfile(profile: RelayProfile) {
        if (streamState.value.isActive() || session.pendingStart != null) return
        runCatching { relayProfiles.save(_relayProfile.value, profile) }.onSuccess {
            _relayProfile.value = profile
            session.verifier.reset()
            clearConfigurationError()
            if (tokenReadFailed) reloadCredentials()
        }.onFailure { _configurationError.value = UiMessage(R.string.error_token_storage) }
    }

    private fun reloadCredentials() {
        runCatching {
            val loaded = relayProfiles.load()
            val auth = relayProfiles.moqProToken()
            _relayProfile.value = loaded; _authToken.value = auth
            tokenReadFailed = false
        }.onFailure { _configurationError.value = UiMessage(R.string.error_token_storage) }
    }

    fun verifySubscription() {
        val profile = _relayProfile.value
        if (profile.provider == RelayProvider.CLOUDFLARE && profile.canShare() && streamState.value is StreamState.Live)
            session.verifier.verify(profile, _enableAudio.value)
    }

    fun setStreamPath(path: String) {
        _streamPath.value = path
        clearConfigurationError()
        prefs.edit { putString(KEY_STREAM_PATH, path) }
    }

    fun setTlsVerify(verify: Boolean) {
        _tlsVerify.value = verify
        clearConfigurationError()
        prefs.edit { putBoolean(KEY_TLS_VERIFY, verify) }
    }

    fun setEnableAudio(enabled: Boolean) {
        _enableAudio.value = enabled
        clearConfigurationError()
        prefs.edit { putBoolean(KEY_ENABLE_AUDIO, enabled) }
    }

    fun setAudioChannel(channel: AudioChannel) {
        _audioChannel.value = channel
        clearConfigurationError()
        prefs.edit { putString(KEY_AUDIO_CHANNEL, channel.name) }
    }

    fun setAudioGain(percent: Int) {
        val normalized = AudioSettings.normalizeGain(percent)
        _audioGain.value = normalized
        prefs.edit { putInt(KEY_AUDIO_GAIN, normalized) }
    }

    fun setAudioQuality(quality: AudioQuality) {
        _audioQuality.value = quality
        prefs.edit { putString(KEY_AUDIO_QUALITY, quality.name) }
    }

    fun setLockLandscape(locked: Boolean) {
        _lockLandscape.value = locked
        prefs.edit { putBoolean(KEY_LOCK_LANDSCAPE, locked) }
    }

    fun setBackgroundStreaming(enabled: Boolean) {
        _backgroundStreaming.value = enabled
        prefs.edit { putBoolean(KEY_BACKGROUND_STREAMING, enabled) }
    }

    fun setLanguage(language: AppLanguage) {
        _language.value = language
        prefs.edit { putString(KEY_LANGUAGE, language.name) }
    }

    fun setTheme(theme: AppTheme) {
        _theme.value = theme
        prefs.edit { putString(KEY_THEME, theme.name) }
    }

    fun setShowCameraDetails(show: Boolean) {
        _showCameraDetails.value = show
        prefs.edit { putBoolean(KEY_SHOW_CAMERA_DETAILS, show) }
    }

    fun setVideoCodec(codec: String) {
        val normalized = codec.uppercase().takeIf { it == "H264" || it == "H265" } ?: return
        _videoCodec.value = normalized
        clearConfigurationError()
        prefs.edit { putString(KEY_VIDEO_CODEC, normalized) }
    }

    fun setVideoResolution(size: Size) {
        if (size.width < 2 || size.height < 2) return
        _videoResolution.value = size
        clearConfigurationError()
        prefs.edit {
            putInt(KEY_VIDEO_WIDTH, size.width)
            putInt(KEY_VIDEO_HEIGHT, size.height)
        }
        syncCamera()
    }

    fun setVideoFps(fps: Int) {
        if (fps !in SUPPORTED_FPS) return
        _videoFps.value = fps
        clearConfigurationError()
        prefs.edit { putInt(KEY_VIDEO_FPS, fps) }
        syncCamera()
    }

    fun setVideoBitrateKbps(value: String) {
        _videoBitrateKbps.value = value
        clearConfigurationError()
        prefs.edit { putString(KEY_VIDEO_BITRATE, value) }
    }

    fun setAdaptiveBitrate(enabled: Boolean) {
        _adaptiveBitrate.value = enabled
        clearConfigurationError()
        prefs.edit { putBoolean(KEY_ADAPTIVE_BITRATE, enabled) }
    }

    fun setConfigurationError(message: UiMessage?) {
        _configurationError.value = message
        if (message != null) diagnostics.event("configuration_error", mapOf("message" to message))
    }

    fun onLifecycleEvent(event: String) {
        diagnostics.event("activity_lifecycle", mapOf("event" to event, "stream_state" to streamState.value.javaClass.simpleName))
    }

    fun onCameraEvent(event: String) {
        diagnostics.event("camera_lifecycle", mapOf("event" to event))
    }

    fun onCameraError(error: Throwable) {
        diagnostics.error("camera_error", error)
        setConfigurationError(UiMessage(R.string.error_camera))
    }

    internal fun exportDiagnostics(): Uri = diagnostics.export(mapOf(
        "stream_state" to streamState.value,
        "moq_core" to coreState.value,
        "configuration_error" to configurationError.value,
        "relay" to getDisplayRelayUrl(),
        "path" to getEffectiveStreamPath(),
        "provider" to relayProfile.value.provider,
        "endpoint_draft" to relayProfile.value.protocolDraft.takeIf { relayProfile.value.provider == RelayProvider.CLOUDFLARE },
        "negotiated_draft" to "not exposed by SDK 0.5.3",
        "subscriber_verification" to subscriberVerification.value,
        "auth_configured" to authToken.value.isNotBlank(),
        "tls_verify" to tlsVerify.value,
        "codec" to videoCodec.value,
        "resolution" to videoResolution.value,
        "fps" to videoFps.value,
        "target_bitrate_kbps" to videoBitrateKbps.value,
        "adaptive_bitrate" to adaptiveBitrate.value,
        "camera_capture" to cameraCaptureStatus.value,
        "audio_enabled" to enableAudio.value,
        "audio_channel_requested" to audioChannel.value,
        "audio_gain_percent" to audioGain.value,
        "audio_quality" to audioQuality.value,
        "lock_landscape" to lockLandscape.value,
        "background_streaming" to backgroundStreaming.value,
        "camera_controls" to cameraControlState.value,
        "video_input" to "gpu_surface",
        "audio_capture" to audioCaptureStatus.value,
        "app_language" to language.value,
        "app_theme" to theme.value,
        "show_camera_details" to showCameraDetails.value,
        "front_camera" to useFrontCamera.value
    ))

    private fun clearConfigurationError() {
        if (_configurationError.value != null) _configurationError.value = null
        session.cameraError.value = null
    }

    fun toggleCamera() {
        val next = !_useFrontCamera.value
        _useFrontCamera.value = next
        diagnostics.event("camera_selected", mapOf("front" to next))
        clearConfigurationError()
        prefs.edit { putBoolean(KEY_USE_FRONT_CAMERA, next) }
        refreshAvailableResolutions(next)
        syncCamera()
    }

    private fun syncCamera() = session.configureCamera(_useFrontCamera.value, _videoResolution.value, _videoFps.value)

    private fun refreshAvailableResolutions(useFront: Boolean) {
        try {
            val cameraManager = getApplication<Application>().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val facing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                    val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
                        ?: map.getOutputSizes(SurfaceTexture::class.java)
                        ?: emptyArray()

                    val sortedSizes = sizes
                        .map { if (it.width >= it.height) Size(it.width, it.height) else Size(it.height, it.width) }
                        .distinctBy { "${it.width}x${it.height}" }
                        .filter { it.width >= 320 && it.width <= 3840 && it.width % 2 == 0 && it.height % 2 == 0 }
                        .sortedByDescending { it.width * it.height }

                    if (sortedSizes.isNotEmpty()) {
                        _availableResolutions.value = sortedSizes
                        if (!sortedSizes.any { it.width == _videoResolution.value.width && it.height == _videoResolution.value.height }) {
                            setVideoResolution(sortedSizes.firstOrNull { it.width == 1280 && it.height == 720 } ?: sortedSizes.first())
                        }
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error querying camera resolutions", e)
        }

        val fallback = listOf(
            Size(1920, 1080),
            Size(1280, 720),
            Size(854, 480),
            Size(640, 480)
        )
        _availableResolutions.value = fallback
    }

    fun onVideoFrame(imageProxy: ImageProxy) {
        streamer.onVideoFrame(imageProxy)
    }

    fun getEffectiveRelayUrl(): String {
        if (_relayProfile.value.provider == RelayProvider.CLOUDFLARE) return _relayProfile.value.publishUrl()
        return RelayUrl.withJwt(_relayUrl.value, _authToken.value)
    }

    fun getDisplayRelayUrl(): String {
        if (_relayProfile.value.provider == RelayProvider.CLOUDFLARE)
            return "https://draft-${_relayProfile.value.protocolDraft}.cloudflare.mediaoverquic.com/[redacted]"
        return RelayUrl.maskJwt(getEffectiveRelayUrl())
    }

    fun getEffectiveStreamPath(): String = if (_relayProfile.value.provider == RelayProvider.CLOUDFLARE)
        _relayProfile.value.broadcastName.trim() else _streamPath.value.trim()

    /** Starts a broadcast only when the current settings are usable. */
    fun startBroadcast(): Boolean {
        if (streamState.value.isActive()) return false
        if (!streamer.canStart) {
            _configurationError.value = UiMessage(R.string.error_stream_stopping)
            return false
        }
        if (!coreState.value.ready) {
            _configurationError.value = UiMessage(R.string.core_required)
            return false
        }
        val validationError = validateBroadcastSettings()
        if (validationError != null) {
            _configurationError.value = validationError
            diagnostics.event("configuration_error", mapOf("message" to validationError))
            return false
        }

        _configurationError.value = null
        DiagnosticRedactor.protectSecrets(_authToken.value, _relayProfile.value.publishToken, _relayProfile.value.subscribeToken)
        val codec = if (_videoCodec.value == "H265") VideoCodec.H265 else VideoCodec.H264
        try { session.requestStart(BroadcastConfiguration(
            relayUrl = getEffectiveRelayUrl(),
            streamPath = getEffectiveStreamPath(),
            tlsVerify = _relayProfile.value.provider == RelayProvider.CLOUDFLARE || _tlsVerify.value,
            enableAudio = _enableAudio.value,
            audioChannel = _audioChannel.value,
            audioQuality = _audioQuality.value,
            audioGainPercent = _audioGain.value,
            codec = codec,
            framerate = _videoFps.value,
            bitrateBps = checkNotNull(VideoBitrateSettings.parseKbps(_videoBitrateKbps.value)),
            adaptiveBitrate = _adaptiveBitrate.value,
            provider = _relayProfile.value.provider,
            protocolDraft = _relayProfile.value.protocolDraft.takeIf { _relayProfile.value.provider == RelayProvider.CLOUDFLARE }
        )) } catch (error: Throwable) {
            diagnostics.error("foreground_service_start_failed", error)
            _configurationError.value = UiMessage(R.string.error_background_start)
            return false
        }
        return true
    }

    private fun validateBroadcastSettings(): UiMessage? {
        if (tokenReadFailed) return UiMessage(R.string.error_token_storage)
        val profile = _relayProfile.value
        if (profile.provider == RelayProvider.CLOUDFLARE) {
            if (!CloudflareRelay.validToken(profile.publishToken)) return UiMessage(R.string.error_cloudflare_publish_token)
            if (profile.protocolDraft != CloudflareRelay.DRAFT) return UiMessage(R.string.error_cloudflare_draft)
            if (!CloudflareRelay.validBroadcast(profile.broadcastName)) return UiMessage(R.string.error_cloudflare_broadcast)
        }
        val relay = _relayUrl.value.trim()
        if (profile.provider == RelayProvider.MOQ_PRO && relay.isEmpty()) return UiMessage(R.string.error_relay_required)

        val uri = runCatching { java.net.URI(relay) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (profile.provider == RelayProvider.MOQ_PRO && (uri == null || scheme == null || scheme !in RELAY_SCHEMES || uri.rawAuthority.isNullOrBlank())) {
            return UiMessage(R.string.error_relay_invalid)
        }

        val path = getEffectiveStreamPath()
        if (path.isEmpty() || path.any { it.isWhitespace() }) {
            return UiMessage(R.string.error_path_invalid)
        }

        if (_videoFps.value !in SUPPORTED_FPS) {
            return UiMessage(R.string.error_fps_invalid)
        }

        if (VideoBitrateSettings.parseKbps(_videoBitrateKbps.value) == null) {
            return UiMessage(R.string.error_bitrate_invalid)
        }

        val resolution = _videoResolution.value
        if (resolution.width < 2 || resolution.height < 2) {
            return UiMessage(R.string.error_resolution_invalid)
        }

        return null
    }

    fun stopBroadcast() {
        session.stop()
    }

    override fun onCleared() {
        super.onCleared()
        diagnostics.event("viewmodel_cleared")
    }
}
