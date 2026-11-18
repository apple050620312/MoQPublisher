package com.sangege.moqpublisher

import android.app.Application
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageProxy
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.moq.VideoCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
private const val KEY_VIDEO_MIN_BITRATE = "video_min_bitrate_kbps"
private const val KEY_VIDEO_MAX_BITRATE = "video_max_bitrate_kbps"
private const val KEY_ADAPTIVE_BITRATE = "adaptive_bitrate"
private const val KEY_ACTIVE_PRESET = "active_stream_preset"
private const val KEY_AUDIO_CHANNEL = "audio_channel"
private const val KEY_AUDIO_GAIN = "audio_gain_percent"
private const val KEY_AUDIO_QUALITY = "audio_quality"
private const val KEY_AUDIO_INPUT_DEVICE_ID = "audio_input_device_id"
private const val KEY_LOCK_LANDSCAPE = "lock_landscape"
private const val KEY_BACKGROUND_STREAMING = "background_streaming"
private const val KEY_LANGUAGE = "app_language"
private const val KEY_THEME = "app_theme"
private const val KEY_SHOW_CAMERA_DETAILS = "show_camera_details"
private const val KEY_CONFIRM_STOP = "confirm_broadcast_stop"
private const val KEY_CAMERA_LENS = "camera_lens"
private const val KEY_VIDEO_STABILIZE = "video_stabilize"
private const val KEY_VIDEO_DIRECTION = "video_direction"
private const val KEY_VIDEO_MIRROR_FRONT = "video_mirror_front"
private const val KEY_RECORDING_MODE = "recording_mode"
private const val KEY_RECORDING_SEGMENT = "recording_segment_minutes"

private const val DEFAULT_RELAY_URL = "https://cdn.moq.pro/placeholder"
private const val DEFAULT_STREAM_PATH = "live/camera"
private const val DEFAULT_VIDEO_WIDTH = 1280
private const val DEFAULT_VIDEO_HEIGHT = 720
private const val DEFAULT_VIDEO_FPS = 30
private val SUPPORTED_FPS = setOf(15, 24, 30, 60)
private val RELAY_SCHEMES = setOf("https", "wss")

class MainViewModel internal constructor(
    application: Application, private val prefs: SharedPreferences,
    tokenStore: ConnectionTokenStore = KeystoreConnectionTokenStore(application)
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val session = (application as MoqApplication).session
    private val diagnostics = session.diagnostics
    private val core = (application as MoqApplication).core
    private val appUpdates = (application as MoqApplication).appUpdates
    private val presetRepository = StreamPresetRepository(prefs)
    private val capabilityRepository = DeviceCapabilityRepository()
    private var applyingPreset = false
    val coreState = core.state
    val appUpdateState = appUpdates.state
    val cameraControls = session.controls
    val cameraControlState: StateFlow<CameraControlState> = cameraControls.state
    val streamer = session.streamer
    val broadcastLifecycle: StateFlow<BroadcastLifecycleState> = streamer.lifecycleState
    val streamHealth: StateFlow<StreamHealthSnapshot> = streamer.healthState
    val streamAlerts: StateFlow<StreamAlertState> = streamer.alertState
    private val _streamAlertMode = MutableStateFlow(
        StreamAlertMode.fromPreference(prefs.getString(StreamAlertMode.PREFERENCE, null))
    )
    val streamAlertMode = _streamAlertMode.asStateFlow()
    private val _confirmStop = MutableStateFlow(prefs.getBoolean(KEY_CONFIRM_STOP, true))
    val confirmStop = _confirmStop.asStateFlow()
    val deviceHealth: StateFlow<DeviceHealthSnapshot> = streamer.deviceHealth
    private val _preflightReport = MutableStateFlow<BroadcastPreflightReport?>(null)
    val preflightReport: StateFlow<BroadcastPreflightReport?> = _preflightReport.asStateFlow()
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
    private val audioInputRepository = AudioInputDeviceRepository(application)
    private val _audioInputDevices = MutableStateFlow(audioInputRepository.list())
    val audioInputDevices: StateFlow<List<AudioInputDevice>> = _audioInputDevices.asStateFlow()
    private val _audioInputDeviceId = MutableStateFlow(
        prefs.getInt(KEY_AUDIO_INPUT_DEVICE_ID, DEFAULT_AUDIO_INPUT_DEVICE_ID)
    )
    val audioInputDeviceId: StateFlow<Int> = _audioInputDeviceId.asStateFlow()
    private val _lockLandscape = MutableStateFlow(prefs.getBoolean(KEY_LOCK_LANDSCAPE, false))
    val lockLandscape = _lockLandscape.asStateFlow()
    private val _backgroundStreaming = MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND_STREAMING, true))
    val backgroundStreaming = _backgroundStreaming.asStateFlow()
    val audioCaptureStatus: StateFlow<AudioCaptureStatus?> = streamer.audioCaptureStatus
    val audioMuted: StateFlow<Boolean> = streamer.audioMuted
    val audioLevel: StateFlow<AudioLevel> = streamer.audioLevel
    val recordingState: StateFlow<RecordingState> = streamer.recordingState
    val screenCaptureState: StateFlow<ScreenCaptureState> = session.screenCaptureState
    internal val overlay = session.overlay
    val remoteControlState: StateFlow<RemoteControlState> = session.remote.state

    fun enableRemote(access: RemoteAccess, generation: Long) = session.enableRemote(access, generation)
    fun revokeRemote() = session.remote.revoke()
    fun copyRemotePairingCode(): Boolean = !screenCaptureState.value.busy && session.remote.copyPairingCode()
    val preferScreen = session.preferScreen
    private val _capturePlaybackAudio = MutableStateFlow(
        Build.VERSION.SDK_INT >= 29 && prefs.getBoolean("screen_playback_audio", false)
    )
    val capturePlaybackAudio = _capturePlaybackAudio.asStateFlow()
    private val _recordingPreferences = MutableStateFlow(RecordingPreferences(
        mode = runCatching { RecordingMode.valueOf(prefs.getString(KEY_RECORDING_MODE, null) ?: RecordingMode.STREAM_ONLY.name) }
            .getOrDefault(RecordingMode.STREAM_ONLY),
        segmentMinutes = RecordingPreferences.normalizeSegmentMinutes(prefs.getInt(KEY_RECORDING_SEGMENT, 15))
    ))
    val recordingPreferences = _recordingPreferences.asStateFlow()

    suspend fun recordings(): List<String> = withContext(Dispatchers.IO) { streamer.recordings() }

    suspend fun exportRecording(name: String, destination: android.net.Uri) = withContext(Dispatchers.IO) {
        streamer.exportRecording(name, destination)
    }

    private val _relayUrl = MutableStateFlow(
        prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
    )
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    private val _authToken = MutableStateFlow(
        runCatching { migrateMoqProRelayCredential() }.getOrElse {
            _configurationError.value = UiMessage(R.string.error_token_storage)
            tokenReadFailed = true
            ""
        }
    )
    val authToken: StateFlow<String> = _authToken.asStateFlow()
    private val _moqProSubscribeToken = MutableStateFlow(
        runCatching { relayProfiles.moqProSubscribeToken() }.getOrDefault("")
    )
    val moqProSubscribeToken = _moqProSubscribeToken.asStateFlow()

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
    val changingVideoMode = session.changingVideoMode
    private val cameraLensRepository = CameraLensRepository(application)
    private val _cameraLenses = MutableStateFlow<List<CameraLens>>(emptyList())
    internal val cameraLenses = _cameraLenses.asStateFlow()
    private val _cameraVideoSettings = MutableStateFlow(CameraVideoSettings(
        lensId = prefs.getString(KEY_CAMERA_LENS, null),
        stabilize = prefs.getBoolean(KEY_VIDEO_STABILIZE, false),
        direction = VideoDirection.fromPreference(prefs.getString(KEY_VIDEO_DIRECTION, null)),
        mirrorFront = prefs.getBoolean(KEY_VIDEO_MIRROR_FRONT, false)
    ))
    internal val cameraVideoSettings = _cameraVideoSettings.asStateFlow()

    private val _videoBitrate = MutableStateFlow(
        VideoBitrateInput.restore(
            target = prefs.getString(KEY_VIDEO_BITRATE, null)
                ?: VideoBitrateSettings.migrateMbps(prefs.getString(KEY_LEGACY_VIDEO_BITRATE, null))
                    .also { migrated -> prefs.edit { putString(KEY_VIDEO_BITRATE, migrated) } },
            minimum = prefs.getString(KEY_VIDEO_MIN_BITRATE, null),
            maximum = prefs.getString(KEY_VIDEO_MAX_BITRATE, null),
            adaptive = prefs.getBoolean(KEY_ADAPTIVE_BITRATE, true)
        )
    )
    internal val videoBitrate = _videoBitrate.asStateFlow()
    private val _applyingVideoBitrate = MutableStateFlow(false)
    internal val applyingVideoBitrate = _applyingVideoBitrate.asStateFlow()
    private val _presets = MutableStateFlow(StreamPresetPolicy.builtIns() + presetRepository.loadCustom())
    val presets: StateFlow<List<StreamPreset>> = _presets.asStateFlow()
    private val _encoderCapabilities = MutableStateFlow<List<VideoEncoderCapability>>(emptyList())
    val encoderCapabilities: StateFlow<List<VideoEncoderCapability>> = _encoderCapabilities.asStateFlow()
    private val _selectedPresetId = MutableStateFlow(prefs.getString(KEY_ACTIVE_PRESET, null))
    val selectedPresetId: StateFlow<String?> = _selectedPresetId.asStateFlow()

    val cameraCaptureStatus = session.camera.captureStatus

    private val _availableResolutions = MutableStateFlow<List<Size>>(emptyList())
    val availableResolutions: StateFlow<List<Size>> = _availableResolutions.asStateFlow()

    init {
        streamer.setAudioInputDevice(_audioInputDeviceId.value)
        refreshAvailableResolutions(_useFrontCamera.value)
        syncCamera()
        viewModelScope.launch(Dispatchers.IO) {
            _encoderCapabilities.value = capabilityRepository.inspectAll()
            _cameraLenses.value = cameraLensRepository.list()
        }
        viewModelScope.launch { session.cameraError.collect { if (it != null) _configurationError.value = it } }
    }

    fun checkCoreUpdates() = core.checkForUpdates()

    fun checkAppUpdates() = appUpdates.checkForUpdates()

    fun setAudioMuted(muted: Boolean) = streamer.setAudioMuted(muted)

    fun applyDeviceHealthRecommendation() {
        if (streamState.value !is StreamState.Live) return
        val generation = broadcastLifecycle.value.generation
        runVideoBitrateChange { streamer.lowerVideoBitrateForDeviceHealth(generation) }
    }

    fun refreshAudioInputDevices() {
        _audioInputDevices.value = audioInputRepository.list()
        if (_audioInputDeviceId.value != DEFAULT_AUDIO_INPUT_DEVICE_ID &&
            _audioInputDevices.value.none { it.id == _audioInputDeviceId.value }) {
            setAudioInputDevice(DEFAULT_AUDIO_INPUT_DEVICE_ID)
        }
    }

    fun setAudioInputDevice(id: Int) {
        val normalized = if (id == DEFAULT_AUDIO_INPUT_DEVICE_ID || _audioInputDevices.value.any { it.id == id }) id
        else DEFAULT_AUDIO_INPUT_DEVICE_ID
        _audioInputDeviceId.value = normalized
        prefs.edit { putInt(KEY_AUDIO_INPUT_DEVICE_ID, normalized) }
        streamer.setAudioInputDevice(normalized)
        clearConfigurationError()
    }

    fun clearPreflightReport() {
        _preflightReport.value = null
    }

    fun downloadAppUpdate() {
        if (!streamState.value.isActive()) appUpdates.downloadUpdate()
    }

    suspend fun prepareAppUpdateInstall(): Uri? {
        if (streamState.value.isActive()) {
            setConfigurationError(UiMessage(R.string.app_update_streaming))
            return null
        }
        return try {
            val (status, uri) = withContext(Dispatchers.IO) {
                val status = appUpdates.installStatus()
                val uri = if (status == AppUpdateInstallStatus.READY) appUpdates.downloadedFile()?.let { file ->
                    FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.updates", file)
                } else null
                // Exercise the actual registered provider before asking another app to read it.
                uri?.let { getApplication<Application>().contentResolver.openFileDescriptor(it, "r")?.use { descriptor ->
                    check(descriptor.statSize > 0) { "Empty update URI" }
                } ?: error("Cannot open update URI") }
                status to uri
            }
            if (streamState.value.isActive()) {
                setConfigurationError(UiMessage(R.string.app_update_streaming))
                null
            } else {
                setConfigurationError(status.errorMessage())
                uri
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.error("app_update_install_preparation", error)
            setConfigurationError(UiMessage(R.string.app_update_invalid_apk))
            null
        }
    }

    fun setRelayUrl(url: String) {
        if (streamState.value.isActive() || session.pendingStart != null) return
        val (address, embeddedJwt) = RelayUrl.splitJwt(url)
        if (embeddedJwt != null) {
            try {
                relayProfiles.saveMoqProToken(embeddedJwt)
                _authToken.value = embeddedJwt
            } catch (error: Exception) {
                diagnostics.error("relay_credential_storage_failed", error)
                setConfigurationError(UiMessage(R.string.error_token_storage))
                return
            }
        }
        _relayUrl.value = address
        clearConfigurationError()
        prefs.edit { putString(KEY_RELAY_URL, address) }
        session.verifier.reset()
    }

    private fun migrateMoqProRelayCredential(): String {
        val (address, jwt) = RelayUrl.splitJwt(_relayUrl.value)
        if (jwt == null) return relayProfiles.moqProToken()
        relayProfiles.saveMoqProToken(jwt)
        prefs.edit { putString(KEY_RELAY_URL, address) }
        _relayUrl.value = address
        return jwt
    }

    fun setMoqProSubscribeToken(token: String) {
        if (streamState.value.isActive() || session.pendingStart != null) return
        runCatching { relayProfiles.saveMoqProSubscribeToken(token) }.onSuccess {
            _moqProSubscribeToken.value = token
            session.verifier.reset()
            clearConfigurationError()
        }.onFailure { setConfigurationError(UiMessage(R.string.error_token_storage)) }
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
            val auth = migrateMoqProRelayCredential()
            _relayProfile.value = loaded; _authToken.value = auth
            tokenReadFailed = false
        }.onFailure { _configurationError.value = UiMessage(R.string.error_token_storage) }
    }

    fun verifySubscription() {
        if (!streamState.value.canVerifyRemoteMedia()) return
        subscriptionTarget()?.verify(session.verifier)
    }

    private fun subscriptionTarget(): BroadcastSubscription? {
        val profile = _relayProfile.value
        DiagnosticRedactor.protectSecrets(profile.publishToken, profile.subscribeToken, _authToken.value, _moqProSubscribeToken.value)
        return when (profile.provider) {
            RelayProvider.CLOUDFLARE -> if (profile.canSubscribe()) {
                BroadcastSubscription(profile.subscribeUrl(), profile.publicationPath(""), profile.provider, _enableAudio.value)
            } else null
            RelayProvider.MOQ_PRO -> moqProSubscriptionUrl()?.let { url ->
                BroadcastSubscription(url, getEffectiveStreamPath(), profile.provider, _enableAudio.value)
            }
        }
    }

    fun moqProSubscriptionUrl(): String? =
        RelayUrl.moqProSubscriptionUrl(_relayUrl.value, _authToken.value, _moqProSubscribeToken.value)

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
        markPresetChanged()
        clearConfigurationError()
        prefs.edit { putBoolean(KEY_ENABLE_AUDIO, enabled) }
    }

    fun setAudioChannel(channel: AudioChannel) {
        _audioChannel.value = channel
        markPresetChanged()
        clearConfigurationError()
        prefs.edit { putString(KEY_AUDIO_CHANNEL, channel.name) }
    }

    fun setAudioGain(percent: Int) {
        val normalized = AudioSettings.normalizeGain(percent)
        _audioGain.value = normalized
        streamer.setAudioGain(normalized)
        prefs.edit { putInt(KEY_AUDIO_GAIN, normalized) }
    }

    fun setAudioQuality(quality: AudioQuality) {
        _audioQuality.value = quality
        markPresetChanged()
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

    fun setStreamAlertMode(mode: StreamAlertMode) {
        _streamAlertMode.value = mode
        prefs.edit { putString(StreamAlertMode.PREFERENCE, mode.name) }
    }

    fun setConfirmStop(confirm: Boolean) {
        _confirmStop.value = confirm
        prefs.edit { putBoolean(KEY_CONFIRM_STOP, confirm) }
    }

    fun setRecordingPreferences(mode: RecordingMode, segmentMinutes: Int) {
        if (streamState.value.isActive()) return
        val next = RecordingPreferences(mode, RecordingPreferences.normalizeSegmentMinutes(segmentMinutes))
        _recordingPreferences.value = next
        prefs.edit {
            putString(KEY_RECORDING_MODE, next.mode.name)
            putInt(KEY_RECORDING_SEGMENT, next.segmentMinutes)
        }
    }

    fun screenCapturePermissionIntent(): Intent = session.screenCapture.permissionIntent()

    fun setPreferScreen(enabled: Boolean) = session.setPreferScreen(enabled)

    fun setCapturePlaybackAudio(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 29 || streamState.value.isActive() || session.pendingStart != null) return
        _capturePlaybackAudio.value = enabled
        prefs.edit { putBoolean("screen_playback_audio", enabled) }
    }

    fun sessionGeneration(): Long = session.generation

    private fun screenRequest(consent: Intent): ScreenCaptureRequest {
        val size = _videoResolution.value
        return ScreenCaptureRequest(consent, maxOf(size.width, size.height), _videoFps.value)
    }

    fun startScreenCapture(data: Intent, generation: Long, startBroadcast: Boolean): Boolean {
        if (session.generation != generation) return false
        return if (startBroadcast) startBroadcast(data) else session.requestScreenCapture(generation, screenRequest(data))
    }

    fun stopScreenCapture() = session.stopScreenCapture()

    fun setVideoCodec(codec: String) {
        val normalized = codec.uppercase().takeIf { it == "H264" || it == "H265" } ?: return
        _videoCodec.value = normalized
        markPresetChanged()
        clearConfigurationError()
        prefs.edit { putString(KEY_VIDEO_CODEC, normalized) }
    }

    fun setVideoResolution(size: Size) {
        applyVideoMode(size, _videoFps.value)
    }

    fun setVideoFps(fps: Int) {
        applyVideoMode(_videoResolution.value, fps)
    }

    fun applyVideoMode(size: Size, fps: Int) {
        applyVideoMode(currentVideoMode().copy(size = size, fps = fps))
    }

    internal fun applyCameraVideoSettings(settings: CameraVideoSettings) {
        applyVideoMode(currentVideoMode().copy(camera = settings))
    }

    private fun currentVideoMode() = VideoMode(_videoResolution.value, _videoFps.value, _cameraVideoSettings.value)

    private fun applyVideoMode(requested: VideoMode) {
        if (requested.size.width < 2 || requested.size.height < 2 || requested.fps !in SUPPORTED_FPS ||
            changingVideoMode.value || session.pendingStart != null) return
        val previous = currentVideoMode()
        if (requested == previous) return
        if (!streamState.value.isActive()) {
            saveVideoMode(requested)
            syncCamera()
            return
        }
        val front = _useFrontCamera.value
        viewModelScope.launch {
            withContext(NonCancellable) {
                if (session.changeVideoMode(front, previous, requested)) saveVideoMode(requested)
                else if (streamState.value.isActive()) setConfigurationError(UiMessage(
                    if (requested.camera != previous.camera) R.string.error_live_camera_mode else R.string.error_live_video_mode
                ))
            }
        }
    }

    private fun saveVideoMode(mode: VideoMode) {
        val sizeOrFpsChanged = mode.size != _videoResolution.value || mode.fps != _videoFps.value
        _videoResolution.value = mode.size
        _videoFps.value = mode.fps
        _cameraVideoSettings.value = mode.camera
        val lens = _cameraLenses.value.firstOrNull { it.id == mode.camera.lensId }
        if (lens != null) _useFrontCamera.value = lens.front
        if (sizeOrFpsChanged) markPresetChanged()
        clearConfigurationError()
        prefs.edit {
            putInt(KEY_VIDEO_WIDTH, mode.size.width)
            putInt(KEY_VIDEO_HEIGHT, mode.size.height)
            putInt(KEY_VIDEO_FPS, mode.fps)
            putString(KEY_CAMERA_LENS, mode.camera.lensId)
            putBoolean(KEY_VIDEO_STABILIZE, mode.camera.stabilize)
            putString(KEY_VIDEO_DIRECTION, mode.camera.direction.name)
            putBoolean(KEY_VIDEO_MIRROR_FRONT, mode.camera.mirrorFront)
            putBoolean(KEY_USE_FRONT_CAMERA, _useFrontCamera.value)
        }
    }

    internal fun applyVideoBitrate(input: VideoBitrateInput) {
        if (_applyingVideoBitrate.value || session.pendingStart != null) return
        val settings = input.configuration() ?: return
        if (!streamState.value.isActive()) {
            saveVideoBitrate(input)
            clearConfigurationError()
            return
        }
        val generation = broadcastLifecycle.value.generation
        runVideoBitrateChange {
            streamer.changeVideoBitrate(generation, settings).also { applied ->
                if (applied) saveVideoBitrate(input)
            }
        }
    }

    private fun runVideoBitrateChange(change: suspend () -> Boolean) {
        if (_applyingVideoBitrate.value) return
        _applyingVideoBitrate.value = true
        viewModelScope.launch {
            // Finish a submitted codec update and its preference write even if the Activity closes.
            withContext(NonCancellable) {
                try {
                    if (change()) clearConfigurationError()
                } catch (error: UiException) {
                    setConfigurationError(error.uiMessage)
                } catch (error: RuntimeException) {
                    diagnostics.error("video_bitrate_settings_rejected", error)
                    setConfigurationError(UiMessage(R.string.error_bitrate_codec_change))
                } finally {
                    _applyingVideoBitrate.value = false
                }
            }
        }
    }

    private fun saveVideoBitrate(input: VideoBitrateInput) {
        _videoBitrate.value = input
        markPresetChanged()
        prefs.edit { writeVideoBitrate(input) }
    }

    private fun SharedPreferences.Editor.writeVideoBitrate(input: VideoBitrateInput) {
        putString(KEY_VIDEO_BITRATE, input.targetKbps)
        putString(KEY_VIDEO_MIN_BITRATE, input.minimumKbps)
        putString(KEY_VIDEO_MAX_BITRATE, input.maximumKbps)
        putBoolean(KEY_ADAPTIVE_BITRATE, input.adaptive)
    }

    fun setConfigurationError(message: UiMessage?) {
        _configurationError.value = message
        if (message != null) diagnostics.event("configuration_error", mapOf("message" to message))
    }

    fun applyPreset(id: String): Boolean {
        if (streamState.value.isActive() || session.pendingStart != null || _applyingVideoBitrate.value) {
            setConfigurationError(UiMessage(R.string.streaming_settings_locked))
            return false
        }
        val preset = _presets.value.firstOrNull { it.id == id } ?: return false
        val validationError = StreamPresetPolicy.validate(preset)
        if (validationError != null) {
            setConfigurationError(UiMessage(R.string.error_preset_invalid))
            diagnostics.event("preset_rejected", mapOf("id" to id, "codec" to preset.videoCodec, "reason" to validationError))
            return false
        }
        applyingPreset = true
        try {
            _videoCodec.value = preset.videoCodec
            _videoResolution.value = android.util.Size(preset.width, preset.height)
            _videoFps.value = preset.fps
            val bitrate = VideoBitrateInput.restore(
                target = preset.videoBitrateKbps, minimum = _videoBitrate.value.minimumKbps,
                maximum = preset.videoBitrateKbps, adaptive = preset.adaptiveBitrate
            )
            _videoBitrate.value = bitrate
            _enableAudio.value = preset.enableAudio
            _audioChannel.value = preset.audioChannel
            _audioQuality.value = preset.audioQuality
            prefs.edit {
                putString(KEY_VIDEO_CODEC, preset.videoCodec)
                putInt(KEY_VIDEO_WIDTH, preset.width)
                putInt(KEY_VIDEO_HEIGHT, preset.height)
                putInt(KEY_VIDEO_FPS, preset.fps)
                writeVideoBitrate(bitrate)
                putBoolean(KEY_ENABLE_AUDIO, preset.enableAudio)
                putString(KEY_AUDIO_CHANNEL, preset.audioChannel.name)
                putString(KEY_AUDIO_QUALITY, preset.audioQuality.name)
                putString(KEY_ACTIVE_PRESET, preset.id)
            }
            _selectedPresetId.value = preset.id
            syncCamera()
            clearConfigurationError()
            diagnostics.event("preset_applied", mapOf("id" to preset.id, "name" to preset.name))
            return true
        } finally {
            applyingPreset = false
        }
    }

    fun saveCurrentPreset(name: String): Boolean = savePreset(name, template = null)

    fun duplicatePreset(id: String, name: String): Boolean {
        val template = _presets.value.firstOrNull { it.id == id } ?: return false
        return savePreset(name, template)
    }

    private fun savePreset(name: String, template: StreamPreset?): Boolean {
        if (streamState.value.isActive()) {
            setConfigurationError(UiMessage(R.string.streaming_settings_locked))
            return false
        }
        val cleanName = name.trim().take(64)
        if (cleanName.isEmpty()) {
            setConfigurationError(UiMessage(R.string.error_preset_name))
            return false
        }
        val saved = runCatching {
            val existingId = if (template == null)
                _presets.value.firstOrNull { it.id == _selectedPresetId.value && !it.builtIn }?.id else null
            val preset = template?.copy(name = cleanName) ?: currentPreset(cleanName)
            presetRepository.saveCustom(preset, existingId)
        }.getOrElse {
            diagnostics.error("preset_save_failed", it)
            setConfigurationError(UiMessage(R.string.error_preset_save))
            return false
        }
        refreshPresets()
        if (template == null || _selectedPresetId.value == template.id) {
            _selectedPresetId.value = saved.id
            prefs.edit { putString(KEY_ACTIVE_PRESET, saved.id) }
        }
        clearConfigurationError()
        diagnostics.event("preset_saved", mapOf("id" to saved.id, "name" to saved.name))
        return true
    }

    fun deletePreset(id: String): Boolean {
        if (streamState.value.isActive() || _presets.value.firstOrNull { it.id == id }?.builtIn != false) return false
        val deleted = presetRepository.delete(id)
        if (!deleted) return false
        refreshPresets()
        if (_selectedPresetId.value == id) {
            _selectedPresetId.value = null
            prefs.edit { remove(KEY_ACTIVE_PRESET) }
        }
        diagnostics.event("preset_deleted", mapOf("id" to id))
        return true
    }

    fun resetCustomPresets() {
        if (streamState.value.isActive()) return
        presetRepository.deleteAll()
        refreshPresets()
        if (_selectedPresetId.value != null && _presets.value.none { it.id == _selectedPresetId.value }) {
            _selectedPresetId.value = null
            prefs.edit { remove(KEY_ACTIVE_PRESET) }
        }
        diagnostics.event("presets_reset")
    }

    private fun refreshPresets() {
        _presets.value = StreamPresetPolicy.builtIns() + presetRepository.loadCustom()
    }

    private fun currentPreset(name: String): StreamPreset = StreamPreset(
        id = StreamPresetPolicy.CUSTOM_ID,
        name = name,
        videoCodec = _videoCodec.value,
        width = _videoResolution.value.width,
        height = _videoResolution.value.height,
        fps = _videoFps.value,
        videoBitrateKbps = _videoBitrate.value.targetKbps,
        adaptiveBitrate = _videoBitrate.value.adaptive,
        enableAudio = _enableAudio.value,
        audioChannel = _audioChannel.value,
        audioQuality = _audioQuality.value
    )

    private fun markPresetChanged() {
        if (applyingPreset || _selectedPresetId.value == null) return
        _selectedPresetId.value = null
        prefs.edit { remove(KEY_ACTIVE_PRESET) }
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
        "preflight" to preflightReport.value,
        "auth_configured" to authToken.value.isNotBlank(),
        "tls_verify" to tlsVerify.value,
        "codec" to videoCodec.value,
        "resolution" to videoResolution.value,
        "fps" to videoFps.value,
        "target_bitrate_kbps" to videoBitrate.value.targetKbps,
        "min_bitrate_kbps" to videoBitrate.value.minimumKbps,
        "max_bitrate_kbps" to videoBitrate.value.maximumKbps,
        "encoder_capabilities" to encoderCapabilities.value.map { it.toDiagnosticMap() },
        "adaptive_bitrate" to videoBitrate.value.adaptive,
        "runtime_bitrate" to (streamState.value as? StreamState.Live)?.runtimeBitrateSupport,
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
        if (changingVideoMode.value) return
        val next = !_useFrontCamera.value
        _useFrontCamera.value = next
        _cameraVideoSettings.value = _cameraVideoSettings.value.copy(lensId = null)
        diagnostics.event("camera_selected", mapOf("front" to next))
        clearConfigurationError()
        prefs.edit {
            putBoolean(KEY_USE_FRONT_CAMERA, next)
            remove(KEY_CAMERA_LENS)
        }
        refreshAvailableResolutions(next)
        syncCamera()
    }

    private fun syncCamera() = session.configureCamera(_useFrontCamera.value, currentVideoMode())

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

    fun getEffectiveStreamPath(): String = _relayProfile.value.publicationPath(_streamPath.value)

    /** Starts a broadcast only when the current settings are usable. */
    fun startBroadcast(screenConsent: Intent? = null): Boolean {
        if (streamState.value.isActive() || session.pendingStart != null ||
            _applyingVideoBitrate.value || changingVideoMode.value) return false
        if (!streamer.canStart) {
            _configurationError.value = UiMessage(R.string.error_stream_stopping)
            return false
        }
        val screen = preferScreen.value
        if (screen != (screenConsent != null)) return false
        if (!checkStartSettings()) return false

        _configurationError.value = null
        val publishes = _recordingPreferences.value.mode.publishes
        if (publishes) DiagnosticRedactor.protectSecrets(
            _authToken.value, _relayProfile.value.publishToken, _relayProfile.value.subscribeToken
        )
        val codec = if (_videoCodec.value == "H265") VideoCodec.H265 else VideoCodec.H264
        try { session.requestStart(BroadcastConfiguration(
            relayUrl = if (publishes) getEffectiveRelayUrl() else "",
            streamPath = if (publishes) getEffectiveStreamPath() else "",
            tlsVerify = _relayProfile.value.provider == RelayProvider.CLOUDFLARE || _tlsVerify.value,
            enableAudio = _enableAudio.value,
            audioChannel = _audioChannel.value,
            audioQuality = _audioQuality.value,
            audioGainPercent = _audioGain.value,
            codec = codec,
            framerate = _videoFps.value,
            videoBitrate = checkNotNull(_videoBitrate.value.configuration()),
            provider = _relayProfile.value.provider,
            recording = _recordingPreferences.value,
            protocolDraft = _relayProfile.value.protocolDraft.takeIf { _relayProfile.value.provider == RelayProvider.CLOUDFLARE },
            screen = screenConsent?.let(::screenRequest),
            capturePlaybackAudio = screen && _enableAudio.value && capturePlaybackAudio.value,
            subscription = if (publishes) subscriptionTarget() else null
        )) } catch (error: Throwable) {
            diagnostics.error("foreground_service_start_failed", error)
            _configurationError.value = UiMessage(R.string.error_background_start)
            return false
        }
        return true
    }

    fun checkStartSettings(): Boolean {
        val error = runPreflight().errors.firstOrNull() ?: return true
        val message = preflightError(error.id)
        _configurationError.value = message
        diagnostics.event("configuration_error", mapOf("message" to message, "source" to "preflight"))
        return false
    }

    fun runPreflight(): BroadcastPreflightReport {
        val profile = _relayProfile.value
        val context = getApplication<Application>()
        val publishes = _recordingPreferences.value.mode.publishes
        val settingsError = if (publishes) validateBroadcastSettings() else null
        val usesCamera = !preferScreen.value
        val report = BroadcastPreflightPolicy.evaluate(BroadcastPreflightInput(
            cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            microphonePermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notificationPermission = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            audioEnabled = _enableAudio.value,
            cameraAvailable = usesCamera && cameraAvailable(context),
            audioAvailable = audioAvailable(),
            hardwareSurfaceEncoderReported = _encoderCapabilities.value.any { it.codec == _videoCodec.value && it.available },
            // CameraX may choose the nearest supported sensor mode. The actual
            // camera size and MediaCodec configuration are validated when the
            // stream encoder is opened, so a stale capability list must not
            // block an otherwise valid user selection here.
            resolutionFpsValid = _videoResolution.value.width >= 2 &&
                _videoResolution.value.height >= 2 && _videoFps.value in SUPPORTED_FPS,
            relayConfigured = settingsError == null,
            publishTokenConfigured = profile.provider != RelayProvider.CLOUDFLARE || CloudflareRelay.validToken(profile.publishToken),
            subscribeTokenConfigured = profile.subscribeToken.isNotBlank(),
            subscribeTokenRequired = false,
            networkAvailable = publishes && networkAvailable(context),
            moqCoreReady = coreState.value.ready,
            batteryPercent = batteryPercent(context),
            thermalWarning = thermalWarning(context),
            usesCamera = usesCamera,
            publishes = publishes
        ))
        _preflightReport.value = report
        diagnostics.event("broadcast_preflight", mapOf(
            "can_start" to report.canStart,
            "errors" to report.errors.map { it.id },
            "warnings" to report.warnings.map { it.id },
            "checks" to report.checks.map { "${it.id}:${it.severity}" }
        ))
        return report
    }

    private fun preflightError(id: PreflightCheckId): UiMessage = when (id) {
        PreflightCheckId.CAMERA_PERMISSION, PreflightCheckId.CAMERA_AVAILABILITY -> UiMessage(R.string.error_camera)
        PreflightCheckId.MICROPHONE_PERMISSION, PreflightCheckId.AUDIO_AVAILABILITY -> UiMessage(R.string.error_microphone_permission)
        PreflightCheckId.RESOLUTION_FPS -> UiMessage(R.string.error_resolution_invalid)
        PreflightCheckId.PUBLISH_TOKEN -> UiMessage(R.string.error_cloudflare_publish_token)
        PreflightCheckId.MOQ_CORE -> UiMessage(R.string.core_required)
        PreflightCheckId.RELAY_CONFIGURATION -> validateBroadcastSettings() ?: UiMessage(R.string.error_relay_invalid)
        else -> validateBroadcastSettings() ?: UiMessage(R.string.error_relay_invalid)
    }

    private fun cameraAvailable(context: Context): Boolean = runCatching {
        context.getSystemService(CameraManager::class.java).cameraIdList.isNotEmpty()
    }.getOrDefault(false)

    private fun audioAvailable(): Boolean {
        if (!_enableAudio.value) return true
        val mask = if (_audioChannel.value == AudioChannel.STEREO) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        return AudioRecord.getMinBufferSize(AudioSettings.SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT) > 0
    }

    private fun networkAvailable(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@runCatching false
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    private fun batteryPercent(context: Context): Int? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@runCatching null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) (level * 100 / scale) else null
    }.getOrNull()

    private fun thermalWarning(context: Context): Boolean = Build.VERSION.SDK_INT >= 29 && runCatching {
        context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }.getOrDefault(false)

    private fun validateBroadcastSettings(): UiMessage? {
        if (tokenReadFailed) return UiMessage(R.string.error_token_storage)
        val profile = _relayProfile.value
        if (profile.provider == RelayProvider.CLOUDFLARE) {
            if (!CloudflareRelay.validToken(profile.publishToken)) return UiMessage(R.string.error_cloudflare_publish_token)
            if (profile.protocolDraft != CloudflareRelay.DRAFT) return UiMessage(R.string.error_cloudflare_draft)
            if (!CloudflareRelay.validPublicationName(getEffectiveStreamPath())) return UiMessage(R.string.error_cloudflare_broadcast)
        }
        val relay = _relayUrl.value.trim()
        if (profile.provider == RelayProvider.MOQ_PRO && relay.isEmpty()) return UiMessage(R.string.error_relay_required)

        val uri = runCatching { java.net.URI(relay) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (profile.provider == RelayProvider.MOQ_PRO && (uri == null || scheme == null || scheme !in RELAY_SCHEMES || uri.rawAuthority.isNullOrBlank())) {
            return UiMessage(R.string.error_relay_invalid)
        }

        val path = getEffectiveStreamPath()
        if ((profile.provider != RelayProvider.CLOUDFLARE || path.isNotEmpty()) && !StreamPath.isValid(path)) {
            return UiMessage(R.string.error_path_invalid)
        }

        if (_videoFps.value !in SUPPORTED_FPS) {
            return UiMessage(R.string.error_fps_invalid)
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


