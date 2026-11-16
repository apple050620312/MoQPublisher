package com.sangege.moqpublisher

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sangege.moqpublisher.ui.theme.LiveRed
import com.sangege.moqpublisher.ui.theme.MoQPublisherTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val language by viewModel.language.collectAsState()
            val theme by viewModel.theme.collectAsState()
            val state by viewModel.streamState.collectAsState()
            val lockLandscape by viewModel.lockLandscape.collectAsState()
            LaunchedEffect(lockLandscape) {
                requestedOrientation = if (lockLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            val keepAwake = state is StreamState.Connecting || state is StreamState.Live
            DisposableEffect(keepAwake) {
                if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = theme == AppTheme.LIGHT
                    isAppearanceLightNavigationBars = theme == AppTheme.LIGHT
                }
            }
            AppLocale(language) {
                MoQPublisherTheme(darkTheme = theme == AppTheme.DARK) {
                    Surface(Modifier.fillMaxSize()) { MainScreen(viewModel) }
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val windowSize = LocalWindowInfo.current.containerSize
    val wideLayout = windowSize.width > windowSize.height
    val relayUrl by viewModel.relayUrl.collectAsState()
    val authToken by viewModel.authToken.collectAsState()
    val relayProfile by viewModel.relayProfile.collectAsState()
    val subscriberVerification by viewModel.subscriberVerification.collectAsState()
    val streamPath by viewModel.streamPath.collectAsState()
    val tlsVerify by viewModel.tlsVerify.collectAsState()
    val enableAudio by viewModel.enableAudio.collectAsState()
    val audioChannel by viewModel.audioChannel.collectAsState()
    val audioGain by viewModel.audioGain.collectAsState()
    val audioQuality by viewModel.audioQuality.collectAsState()
    val lockLandscape by viewModel.lockLandscape.collectAsState()
    val backgroundStreaming by viewModel.backgroundStreaming.collectAsState()
    val audioCaptureStatus by viewModel.audioCaptureStatus.collectAsState()
    val cameraControlState by viewModel.cameraControlState.collectAsState()
    val language by viewModel.language.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val showCameraDetails by viewModel.showCameraDetails.collectAsState()
    val useFrontCamera by viewModel.useFrontCamera.collectAsState()
    val videoCodec by viewModel.videoCodec.collectAsState()
    val videoResolution by viewModel.videoResolution.collectAsState()
    val videoFps by viewModel.videoFps.collectAsState()
    val videoBitrateKbps by viewModel.videoBitrateKbps.collectAsState()
    val adaptiveBitrate by viewModel.adaptiveBitrate.collectAsState()
    val availableResolutions by viewModel.availableResolutions.collectAsState()
    val streamState by viewModel.streamState.collectAsState()
    val configurationError by viewModel.configurationError.collectAsState()
    val coreState by viewModel.coreState.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var hasCameraPermission by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hasAudioPermission by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var pendingAudioStart by rememberSaveable { mutableStateOf(false) }
    var notificationsRequested by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.startBroadcast()
    }
    val startWithNotification: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsRequested &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else viewModel.startBroadcast()
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudioPermission = granted
        val shouldStart = pendingAudioStart
        pendingAudioStart = false
        if (shouldStart) {
            if (granted) startWithNotification()
            else viewModel.setConfigurationError(UiMessage(R.string.error_microphone_permission))
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            viewModel.onLifecycleEvent(event.name)
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                hasAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                viewModel.checkAppUpdates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    val active = streamState is StreamState.Live || streamState is StreamState.Connecting
    val installAppUpdate: () -> Unit = {
        viewModel.appUpdateUri()?.let { uri ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: ActivityNotFoundException) {
                viewModel.setConfigurationError(UiMessage(R.string.app_update_install_failed))
            }
        }
    }
    val onToggleBroadcast: () -> Unit = {
        when {
            active -> viewModel.stopBroadcast()
            enableAudio && !hasAudioPermission -> {
                pendingAudioStart = true
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> startWithNotification()
        }
    }
    val openSettings = { showSettings = true }
    BackHandler(enabled = showSettings) { showSettings = false }
    Box(Modifier.fillMaxSize()) {
        if (!hasCameraPermission) {
            PermissionScreen(viewModel, openSettings) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            CameraStage(
                viewModel = viewModel,
                useFrontCamera = useFrontCamera,
                targetResolution = videoResolution,
                targetFramerate = videoFps,
                streamState = streamState,
                configurationError = configurationError,
                relay = viewModel.getDisplayRelayUrl(),
                path = viewModel.getEffectiveStreamPath(),
                codec = videoCodec,
                fps = videoFps,
                audioCaptureStatus = audioCaptureStatus,
                cameraControls = viewModel.cameraControls,
                cameraControlState = cameraControlState,
                showDetails = showCameraDetails,
                wideLayout = wideLayout,
                onToggleBroadcast = onToggleBroadcast,
                onToggleDetails = { viewModel.setShowCameraDetails(!showCameraDetails) },
                onOpenSettings = openSettings
            )
        }
        if (!coreState.ready && !showSettings) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(Modifier.widthIn(max = 520.dp)) {
                    CoreStatusCard(coreState, viewModel::checkCoreUpdates)
                    TextButton(onClick = openSettings, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.settings))
                    }
                }
            }
        }
        // Preserve camera/session ownership behind Settings, including during locale/theme changes.
        if (showSettings) {
            SettingsScreen(
                relayUrl = relayUrl, onRelayUrlChange = viewModel::setRelayUrl,
                authToken = authToken, onAuthTokenChange = viewModel::setAuthToken,
                effectiveUrl = viewModel.getDisplayRelayUrl(),
                relayProfile = relayProfile, onRelayProfileChange = viewModel::setRelayProfile,
                profileError = configurationError,
                subscriberVerification = subscriberVerification, onVerifySubscription = viewModel::verifySubscription,
                canVerifySubscription = (streamState as? StreamState.Live)?.phase?.let { it != RelayConnectionPhase.RECONNECTING } == true,
                streamPath = streamPath, onStreamPathChange = viewModel::setStreamPath,
                videoCodec = videoCodec, onVideoCodecChange = viewModel::setVideoCodec,
                videoResolution = videoResolution, onVideoResolutionChange = viewModel::setVideoResolution,
                availableResolutions = availableResolutions,
                videoFps = videoFps, onVideoFpsChange = viewModel::setVideoFps,
                videoBitrateKbps = videoBitrateKbps, onVideoBitrateChange = viewModel::setVideoBitrateKbps,
                adaptiveBitrate = adaptiveBitrate, onAdaptiveBitrateChange = viewModel::setAdaptiveBitrate,
                enableAudio = enableAudio, onEnableAudioChange = viewModel::setEnableAudio,
                audioChannel = audioChannel, onAudioChannelChange = viewModel::setAudioChannel,
                audioGain = audioGain, onAudioGainChange = viewModel::setAudioGain,
                audioQuality = audioQuality, onAudioQualityChange = viewModel::setAudioQuality,
                audioCaptureStatus = audioCaptureStatus,
                tlsVerify = tlsVerify, onTlsVerifyChange = viewModel::setTlsVerify,
                language = language, onLanguageChange = viewModel::setLanguage,
                theme = theme, onThemeChange = viewModel::setTheme,
                lockLandscape = lockLandscape, onLockLandscapeChange = viewModel::setLockLandscape,
                backgroundStreaming = backgroundStreaming, onBackgroundStreamingChange = viewModel::setBackgroundStreaming,
                coreState = coreState, onCoreCheck = viewModel::checkCoreUpdates,
                appUpdateState = appUpdateState, onAppUpdateCheck = viewModel::checkAppUpdates,
                onAppUpdateDownload = viewModel::downloadAppUpdate, onAppUpdateInstall = installAppUpdate,
                isStreaming = active, onBack = { showSettings = false }
            )
        }
    }
}

@Composable
private fun CameraStage(
    viewModel: MainViewModel,
    useFrontCamera: Boolean,
    targetResolution: Size,
    targetFramerate: Int,
    streamState: StreamState,
    configurationError: UiMessage?,
    relay: String,
    path: String,
    codec: String,
    fps: Int,
    audioCaptureStatus: AudioCaptureStatus?,
    cameraControls: CameraControlHandle,
    cameraControlState: CameraControlState,
    showDetails: Boolean,
    wideLayout: Boolean,
    onToggleBroadcast: () -> Unit,
    onToggleDetails: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            viewModel = viewModel, modifier = Modifier.fillMaxSize()
        )
        if (streamState is StreamState.Live && streamState.phase == RelayConnectionPhase.PUBLISHING && !wideLayout) {
            LiveTag(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp))
        }
        if (wideLayout) {
            CameraLandscapeControls(
                viewModel, streamState, configurationError, relay, path, codec, targetResolution, fps,
                audioCaptureStatus, cameraControls, cameraControlState, showDetails,
                onToggleBroadcast, onToggleDetails, onOpenSettings
            )
        } else if (showDetails) {
            CameraDetailsBottomPanel(
                viewModel, streamState, configurationError, relay, path, codec, targetResolution, fps,
                audioCaptureStatus, cameraControls, cameraControlState, onToggleBroadcast, onToggleDetails, onOpenSettings
            )
        } else {
            CameraOnlyControls(
                streamState, configurationError, cameraControls, cameraControlState, onToggleBroadcast,
                onToggleDetails, onOpenSettings, viewModel::toggleCamera
            )
        }
    }
}

@Composable
private fun CameraLandscapeControls(
    viewModel: MainViewModel,
    state: StreamState,
    configurationError: UiMessage?,
    relay: String,
    path: String,
    codec: String,
    resolution: Size,
    fps: Int,
    audio: AudioCaptureStatus?,
    cameraControls: CameraControlHandle,
    cameraControlState: CameraControlState,
    showDetails: Boolean,
    onToggleBroadcast: () -> Unit,
    onToggleDetails: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // The narrow camera rails stay in place when the separate details panel opens.
    // All overlays share the same PreviewView, keeping the camera session bound.
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
        val detailsWidth = (maxWidth * 0.32f).coerceIn(220.dp, 280.dp).coerceAtMost(maxWidth * 0.42f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                modifier = Modifier.width(56.dp).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), tonalElevation = 4.dp
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(onClick = viewModel::toggleCamera) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = stringResource(R.string.switch_camera))
                    }
                    IconButton(onClick = cameraControls::focusCenter, enabled = cameraControlState.attached) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = stringResource(R.string.focus_center))
                    }
                    IconToggleButton(
                        checked = cameraControlState.torchEnabled,
                        onCheckedChange = { cameraControls.toggleTorch() },
                        enabled = cameraControlState.attached && cameraControlState.hasFlashUnit
                    ) {
                        Icon(
                            if (cameraControlState.torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = stringResource(if (cameraControlState.hasFlashUnit) R.string.toggle_flashlight else R.string.flashlight_unavailable)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    IconButton(onClick = onToggleDetails) {
                        Icon(
                            if (showDetails) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                            contentDescription = stringResource(if (showDetails) R.string.hide_details else R.string.show_details)
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (showDetails) {
                CameraDetailsSidePanel(
                    viewModel, state, configurationError, relay, path, codec, resolution, fps,
                    audio, onToggleBroadcast,
                    Modifier.width(detailsWidth).fillMaxHeight()
                )
            }
            Surface(
                modifier = Modifier.width(64.dp).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), tonalElevation = 4.dp
            ) {
                // Do not wrap a vertical slider in verticalScroll: that would compete
                // with zoom drags. Only the camera actions and details panels scroll.
                Column(
                    Modifier.fillMaxSize().padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CameraVerticalZoomControls(cameraControls, cameraControlState, Modifier.weight(1f))
                    BroadcastButton(state, onToggleBroadcast, compact = true)
                }
            }
        }
        if (!showDetails) {
            (configurationError ?: (state as? StreamState.Error)?.message)?.let {
                StatusNotice(
                    it.text(), error = true,
                    modifier = Modifier.align(Alignment.TopCenter).padding(start = 64.dp, end = 72.dp)
                )
            }
        }
    }
}

@Composable
private fun CameraVerticalZoomControls(cameraControls: CameraControlHandle, state: CameraControlState, modifier: Modifier = Modifier) {
    val enabled = state.attached && state.maxZoomRatio > state.minZoomRatio
    val zoomLabel = stringResource(R.string.zoom)
    val zoomValue = stringResource(R.string.zoom_value, state.zoomRatio)
    val resetLabel = stringResource(R.string.reset_zoom)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CameraExposureControl(cameraControls, state)
        TextButton(
            onClick = cameraControls::resetZoom, enabled = enabled,
            contentPadding = PaddingValues(2.dp),
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = resetLabel
                stateDescription = zoomValue
            }
        ) {
            Text(zoomValue, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Layout(
            modifier = Modifier.weight(1f).width(48.dp),
            content = {
                // A left-to-right slider rotated counterclockwise increases upward
                // in every app language, including when following an RTL system.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Slider(
                        value = state.zoomRatio,
                        onValueChange = cameraControls::setZoomRatio,
                        valueRange = state.minZoomRatio..state.maxZoomRatio,
                        enabled = enabled,
                        modifier = Modifier.rotate(-90f).semantics { contentDescription = zoomLabel }
                    )
                }
            }
        ) { measurables, constraints ->
            // Swap measured dimensions as well as rotating the drawing, keeping
            // the slider's touch and accessibility bounds inside the narrow rail.
            val slider = measurables.single().measure(Constraints(
                minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth, maxHeight = constraints.maxWidth
            ))
            layout(slider.height, slider.width) {
                slider.place((slider.height - slider.width) / 2, (slider.width - slider.height) / 2)
            }
        }
    }
}

@Composable
private fun CameraDetailsSidePanel(
    viewModel: MainViewModel,
    state: StreamState,
    configurationError: UiMessage?,
    relay: String,
    path: String,
    codec: String,
    resolution: Size,
    fps: Int,
    audio: AudioCaptureStatus?,
    onToggleBroadcast: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), tonalElevation = 4.dp
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StreamControls(viewModel, state, configurationError, relay, path, codec, resolution, fps, audio, onToggleBroadcast, showBroadcastButton = false)
        }
    }
}

@Composable
private fun BoxScope.CameraDetailsBottomPanel(
    viewModel: MainViewModel,
    state: StreamState,
    configurationError: UiMessage?,
    relay: String,
    path: String,
    codec: String,
    resolution: Size,
    fps: Int,
    audio: AudioCaptureStatus?,
    cameraControls: CameraControlHandle,
    cameraControlState: CameraControlState,
    onToggleBroadcast: () -> Unit,
    onToggleDetails: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val height = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { height.toDp() * 0.62f }.coerceAtLeast(220.dp)
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), tonalElevation = 4.dp
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CameraPanelHeader(onToggleDetails, onOpenSettings, viewModel::toggleCamera)
            CameraControlBar(cameraControls, cameraControlState)
            StreamControls(viewModel, state, configurationError, relay, path, codec, resolution, fps, audio, onToggleBroadcast)
        }
    }
}

@Composable
private fun CameraPanelHeader(
    onToggleDetails: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchCamera: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.camera_controls), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onSwitchCamera) {
            Icon(Icons.Default.FlipCameraAndroid, contentDescription = stringResource(R.string.switch_camera))
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
        }
        IconButton(onClick = onToggleDetails) {
            Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.hide_details))
        }
    }
}

@Composable
private fun BoxScope.CameraOnlyControls(
    state: StreamState,
    configurationError: UiMessage?,
    cameraControls: CameraControlHandle,
    cameraControlState: CameraControlState,
    onToggleBroadcast: () -> Unit,
    onToggleDetails: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchCamera: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onSwitchCamera) {
                Icon(Icons.Default.FlipCameraAndroid, contentDescription = stringResource(R.string.switch_camera))
            }
            IconButton(onClick = cameraControls::focusCenter, enabled = cameraControlState.attached) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = stringResource(R.string.focus_center))
            }
            IconButton(onClick = cameraControls::toggleTorch, enabled = cameraControlState.hasFlashUnit) {
                Icon(
                    if (cameraControlState.torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = stringResource(if (cameraControlState.hasFlashUnit) R.string.toggle_flashlight else R.string.flashlight_unavailable)
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
            IconButton(onClick = onToggleDetails) {
                Icon(Icons.Default.ExpandLess, contentDescription = stringResource(R.string.show_details))
            }
        }
        configurationError?.let {
            StatusNotice(
                message = it.text(), error = true,
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp)
            )
        }
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), tonalElevation = 4.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraControlBar(cameraControls, cameraControlState, compact = true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StreamStateBadge(state)
                    BroadcastButton(state, onToggleBroadcast, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CameraControlBar(cameraControls: CameraControlHandle, state: CameraControlState, compact: Boolean = false) {
    val zoomEnabled = state.attached && state.maxZoomRatio > state.minZoomRatio
    val zoomLabel = stringResource(R.string.zoom)
    val zoomValue = stringResource(R.string.zoom_value, state.zoomRatio)
    val resetLabel = stringResource(R.string.reset_zoom)
    if (!compact) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = cameraControls::focusCenter, enabled = state.attached) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = stringResource(R.string.focus_center))
            }
            IconButton(onClick = cameraControls::toggleTorch, enabled = state.attached && state.hasFlashUnit) {
                Icon(
                    if (state.torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = stringResource(if (state.hasFlashUnit) R.string.toggle_flashlight else R.string.flashlight_unavailable)
                )
            }
            Text(stringResource(R.string.tap_to_focus_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CameraExposureControl(cameraControls, state)
        IconButton(onClick = { cameraControls.zoomBy(-0.5f) }, enabled = zoomEnabled) {
            Icon(Icons.Default.ZoomOut, contentDescription = stringResource(R.string.zoom_out))
        }
        Slider(
            value = state.zoomRatio,
            onValueChange = cameraControls::setZoomRatio,
            valueRange = state.minZoomRatio..state.maxZoomRatio,
            enabled = zoomEnabled,
            modifier = Modifier.weight(1f).semantics { contentDescription = zoomLabel }
        )
        TextButton(
            onClick = cameraControls::resetZoom, enabled = zoomEnabled,
            contentPadding = PaddingValues(2.dp),
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = resetLabel
                stateDescription = zoomValue
            }
        ) {
            Text(zoomValue, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        IconButton(onClick = { cameraControls.zoomBy(0.5f) }, enabled = zoomEnabled) {
            Icon(Icons.Default.ZoomIn, contentDescription = stringResource(R.string.zoom_in))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamTopBar(viewModel: MainViewModel, onOpenSettings: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = { StreamActions(viewModel, onOpenSettings) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
private fun RowScope.StreamActions(viewModel: MainViewModel, onOpenSettings: () -> Unit) {
    IconButton(onClick = viewModel::toggleCamera) {
        Icon(Icons.Default.FlipCameraAndroid, contentDescription = stringResource(R.string.switch_camera))
    }
    IconButton(onClick = onOpenSettings) {
        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
    }
}

@Composable
private fun LandscapeContent(
    viewModel: MainViewModel, onOpenSettings: () -> Unit,
    preview: @Composable (Modifier) -> Unit, controls: @Composable () -> Unit
) {
    val width = LocalWindowInfo.current.containerSize.width
    val panelWidth = with(LocalDensity.current) { width.toDp() * 0.4f }.coerceIn(264.dp, 360.dp)
    Row(Modifier.fillMaxSize().safeDrawingPadding()) {
        preview(Modifier.weight(1f).fillMaxHeight())
        Surface(Modifier.width(panelWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StreamActions(viewModel, onOpenSettings)
                }
                controls()
            }
        }
    }
}

@Composable
private fun PortraitContent(
    viewModel: MainViewModel, onOpenSettings: () -> Unit,
    preview: @Composable (Modifier) -> Unit, controls: @Composable () -> Unit
) {
    val height = LocalWindowInfo.current.containerSize.height
    val maxPanelHeight = with(LocalDensity.current) { height.toDp() * 0.58f }.coerceAtLeast(160.dp)
    Scaffold(topBar = { StreamTopBar(viewModel, onOpenSettings) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            preview(Modifier.fillMaxWidth().weight(1f))
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(max = maxPanelHeight)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) { controls() }
            }
        }
    }
}

@Composable
private fun StreamControls(
    viewModel: MainViewModel, state: StreamState, configurationError: UiMessage?,
    relay: String, path: String, codec: String, resolution: Size, fps: Int,
    audio: AudioCaptureStatus?,
    onToggleBroadcast: () -> Unit,
    showBroadcastButton: Boolean = true
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StreamStateBadge(state)
        Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    (configurationError ?: (state as? StreamState.Error)?.message)?.let { StatusNotice(it.text(), error = true) }
    when (state) {
        is StreamState.Connecting -> StatusNotice(stringResource(R.string.connecting_relay), loading = true)
        is StreamState.Live -> {
            if (state.phase == RelayConnectionPhase.RECONNECTING)
                StatusNotice(stringResource(R.string.status_reconnecting), loading = true)
            else if (state.phase == RelayConnectionPhase.CONNECTED)
                StatusNotice(stringResource(R.string.connected_waiting_media), loading = true)
            LiveStats(state, codec, fps)
        }
        else -> SettingCard(stringResource(R.string.effective_url)) {
            Text(relay, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.video_summary, codecDisplayName(codec), resolution.width, resolution.height, fps),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    VideoPerformanceInfo(viewModel)
    audio?.let {
        if (it.fellBackToMono) StatusNotice(stringResource(R.string.stereo_fallback))
        Text(stringResource(R.string.audio_capture_summary, channelDisplayName(it.actual), it.gainPercent, it.bitrateBps / 1000),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showBroadcastButton) BroadcastButton(state, onToggleBroadcast)
    DiagnosticsExportButton(viewModel, Modifier.fillMaxWidth().heightIn(min = 48.dp))
}

@Composable
private fun LiveStats(stats: StreamState.Live, codec: String, fps: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.live_stats), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatItem(stringResource(R.string.stat_rtt), stats.rttMs?.let { stringResource(R.string.value_ms, it) }
                    ?: stringResource(R.string.stat_unavailable), Modifier.weight(1f))
                StatItem(stringResource(R.string.stat_bitrate), stats.sendRateKbps?.let { stringResource(R.string.value_mbps, it / 1000.0) }
                    ?: stringResource(R.string.stat_unavailable), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatItem(stringResource(R.string.stat_sent), stats.bytesSentMb?.let { stringResource(R.string.value_mb, it) }
                    ?: stringResource(R.string.stat_unavailable), Modifier.weight(1f))
                StatItem(stringResource(R.string.stat_codec), stringResource(R.string.value_codec_fps, codecDisplayName(codec), fps), Modifier.weight(1f))
            }
            Text(stringResource(R.string.stat_reconnects, stats.reconnectCount), style = MaterialTheme.typography.bodySmall)
            stats.endpointDraft?.let { Text(stringResource(R.string.stat_endpoint_draft, it), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.publishing_not_decode_verified), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BroadcastButton(state: StreamState, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val live = state is StreamState.Live
    val connecting = state is StreamState.Connecting
    val label = stringResource(when {
        live -> R.string.stop_broadcast
        connecting -> R.string.cancel_connection
        else -> R.string.start_broadcast
    })
    val actionIcon: @Composable () -> Unit = {
        if (connecting) CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
        else Icon(if (live) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
    }
    val containerColor = if (live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val contentColor = if (live) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    if (compact) {
        val status = stringResource(when (state) {
            StreamState.Idle -> R.string.status_idle
            StreamState.Connecting -> R.string.status_connecting
            is StreamState.Live -> state.phase.statusResource()
            is StreamState.Error -> R.string.status_error
        })
        FilledIconButton(
            onClick = onClick,
            modifier = modifier.size(48.dp).semantics { contentDescription = label; stateDescription = status },
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor, contentColor = contentColor)
        ) {
            actionIcon()
        }
    } else {
        Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor)) {
            actionIcon()
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun StatusNotice(
    message: String,
    error: Boolean = false,
    loading: Boolean = false,
    success: Boolean = false,
    modifier: Modifier = Modifier
) {
    val background = when {
        error -> MaterialTheme.colorScheme.errorContainer
        success -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val foreground = when {
        error -> MaterialTheme.colorScheme.onErrorContainer
        success -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
        color = background, contentColor = foreground) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            else Icon(when {
                error -> Icons.Default.ErrorOutline
                success -> Icons.Default.CheckCircleOutline
                else -> Icons.Default.Info
            }, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun SettingsScreen(
    relayUrl: String, onRelayUrlChange: (String) -> Unit,
    authToken: String, onAuthTokenChange: (String) -> Unit,
    effectiveUrl: String, streamPath: String, onStreamPathChange: (String) -> Unit,
    relayProfile: RelayProfile, onRelayProfileChange: (RelayProfile) -> Unit,
    profileError: UiMessage?,
    subscriberVerification: SubscriberVerification, onVerifySubscription: () -> Unit, canVerifySubscription: Boolean,
    videoCodec: String, onVideoCodecChange: (String) -> Unit,
    videoResolution: Size, onVideoResolutionChange: (Size) -> Unit,
    availableResolutions: List<Size>, videoFps: Int, onVideoFpsChange: (Int) -> Unit,
    videoBitrateKbps: String, onVideoBitrateChange: (String) -> Unit,
    adaptiveBitrate: Boolean, onAdaptiveBitrateChange: (Boolean) -> Unit,
    enableAudio: Boolean, onEnableAudioChange: (Boolean) -> Unit,
    audioChannel: AudioChannel, onAudioChannelChange: (AudioChannel) -> Unit,
    audioGain: Int, onAudioGainChange: (Int) -> Unit, audioCaptureStatus: AudioCaptureStatus?,
    audioQuality: AudioQuality, onAudioQualityChange: (AudioQuality) -> Unit,
    tlsVerify: Boolean, onTlsVerifyChange: (Boolean) -> Unit,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit,
    theme: AppTheme, onThemeChange: (AppTheme) -> Unit,
    lockLandscape: Boolean, onLockLandscapeChange: (Boolean) -> Unit,
    backgroundStreaming: Boolean, onBackgroundStreamingChange: (Boolean) -> Unit,
    coreState: CoreState, onCoreCheck: () -> Unit,
    appUpdateState: AppUpdateState, onAppUpdateCheck: () -> Unit,
    onAppUpdateDownload: () -> Unit, onAppUpdateInstall: () -> Unit,
    isStreaming: Boolean, onBack: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val editable = !isStreaming
    val bitrateValid = VideoBitrateSettings.parseKbps(videoBitrateKbps) != null
    SettingsScaffold(onBack) {
        profileError?.let { StatusNotice(it.text(), error = true) }
        if (isStreaming) StatusNotice(stringResource(R.string.streaming_settings_locked))
        SettingCard(stringResource(R.string.broadcast_behavior)) {
            SettingSwitch(stringResource(R.string.background_streaming), stringResource(R.string.background_streaming_hint),
                backgroundStreaming, onBackgroundStreamingChange, editable)
            SettingSwitch(stringResource(R.string.lock_landscape), stringResource(R.string.lock_landscape_hint),
                lockLandscape, onLockLandscapeChange, true)
        }
        SettingCard(stringResource(R.string.connection_settings), stringResource(R.string.connection_settings_description)) {
            SelectionDropdown(stringResource(R.string.relay_provider), relayProfile.provider, listOf(
                RelayProvider.MOQ_PRO to stringResource(R.string.provider_moq_pro),
                RelayProvider.CLOUDFLARE to stringResource(R.string.provider_cloudflare)),
                { onRelayProfileChange(relayProfile.copy(provider = it)) }, editable)
            if (relayProfile.provider == RelayProvider.CLOUDFLARE) {
                CloudflareProfilePanel(relayProfile, onRelayProfileChange, editable, subscriberVerification,
                    onVerifySubscription, canVerifySubscription)
            } else {
            OutlinedTextField(value = relayUrl, onValueChange = onRelayUrlChange,
                label = { Text(stringResource(R.string.relay_url)) }, placeholder = { Text(stringResource(R.string.relay_url_example)) },
                leadingIcon = { Icon(Icons.Default.CloudQueue, contentDescription = null) },
                enabled = editable, singleLine = true, shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = streamPath, onValueChange = onStreamPathChange,
                label = { Text(stringResource(R.string.broadcast_path)) }, placeholder = { Text(stringResource(R.string.broadcast_path_example)) },
                leadingIcon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                enabled = editable, singleLine = true, shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), modifier = Modifier.fillMaxWidth())
            if (authToken.isNotBlank()) {
                Text(stringResource(R.string.effective_url), style = MaterialTheme.typography.labelMedium)
                Text(effectiveUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            }
        }
        SettingCard(stringResource(R.string.video_configuration)) {
            ChoiceOptions(stringResource(R.string.video_codec), videoCodec, listOf(
                "H264" to stringResource(R.string.codec_h264), "H265" to stringResource(R.string.codec_h265)), onVideoCodecChange, editable)
            SelectionDropdown(stringResource(R.string.camera_resolution), videoResolution,
                availableResolutions.map { it to formatResolutionLabel(it) }, onVideoResolutionChange, editable)
            ChoiceOptions(stringResource(R.string.target_framerate), videoFps,
                listOf(15, 24, 30, 60).map { it to stringResource(R.string.fps_value, it) }, onVideoFpsChange, editable)
            OutlinedTextField(value = videoBitrateKbps, onValueChange = { onVideoBitrateChange(it.take(32)) },
                label = { Text(stringResource(R.string.target_bitrate)) },
                supportingText = { Text(stringResource(if (bitrateValid) R.string.bitrate_hint else R.string.error_bitrate_invalid)) },
                isError = !bitrateValid, enabled = editable, singleLine = true, shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), modifier = Modifier.fillMaxWidth())
            SettingSwitch(stringResource(R.string.adaptive_bitrate),
                stringResource(if (adaptiveBitrate) R.string.adaptive_bitrate_on else R.string.adaptive_bitrate_off),
                adaptiveBitrate, onAdaptiveBitrateChange, editable)
        }
        SettingCard(stringResource(R.string.audio_settings), stringResource(R.string.audio_settings_description)) {
            SettingSwitch(stringResource(R.string.broadcast_audio), stringResource(R.string.broadcast_audio_hint), enableAudio, onEnableAudioChange, editable)
            ChoiceOptions(stringResource(R.string.audio_channel), audioChannel, listOf(
                AudioChannel.MONO to stringResource(R.string.audio_mono), AudioChannel.STEREO to stringResource(R.string.audio_stereo)),
                onAudioChannelChange, editable && enableAudio)
            SupportingText(stringResource(R.string.audio_channel_hint))
            SelectionDropdown(stringResource(R.string.audio_quality), audioQuality,
                AudioQuality.entries.map { quality -> quality to stringResource(R.string.audio_quality_option,
                    stringResource(when (quality) {
                        AudioQuality.ECONOMY -> R.string.audio_quality_economy
                        AudioQuality.STANDARD -> R.string.audio_quality_standard
                        AudioQuality.HIGH -> R.string.audio_quality_high
                        AudioQuality.MAXIMUM -> R.string.audio_quality_maximum
                    }), quality.bitrateBps(audioChannel) / 1000) }, onAudioQualityChange, editable && enableAudio)
            SupportingText(stringResource(R.string.audio_quality_hint))
            if (audioCaptureStatus?.fellBackToMono == true) StatusNotice(stringResource(R.string.stereo_fallback))
            AudioGainControl(audioGain, onAudioGainChange, editable && enableAudio)
        }
        SettingCard(stringResource(R.string.security_authentication), stringResource(R.string.security_authentication_description)) {
            if (relayProfile.provider == RelayProvider.MOQ_PRO) {
            OutlinedTextField(value = authToken, onValueChange = onAuthTokenChange,
                label = { Text(stringResource(R.string.auth_token)) }, placeholder = { Text(stringResource(R.string.auth_token_example)) },
                supportingText = { Text(stringResource(R.string.auth_token_hint)) },
                leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) }, visualTransformation = PasswordVisualTransformation(),
                enabled = editable, singleLine = true, shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), modifier = Modifier.fillMaxWidth())
            SettingSwitch(stringResource(R.string.tls_verify), stringResource(if (tlsVerify) R.string.tls_strict else R.string.tls_disabled),
                tlsVerify, onTlsVerifyChange, editable)
            } else SupportingText(stringResource(R.string.cloudflare_tls_required))
        }
        SettingCard(stringResource(R.string.appearance), stringResource(R.string.appearance_description)) {
            SelectionDropdown(stringResource(R.string.language), language, listOf(
                AppLanguage.TRADITIONAL_CHINESE to stringResource(R.string.language_zh),
                AppLanguage.ENGLISH to stringResource(R.string.language_en),
                AppLanguage.SYSTEM to stringResource(R.string.language_system)), onLanguageChange)
            ChoiceOptions(stringResource(R.string.theme), theme, listOf(
                AppTheme.DARK to stringResource(R.string.theme_dark), AppTheme.LIGHT to stringResource(R.string.theme_light)), onThemeChange)
        }
        SettingCard(stringResource(R.string.about), stringResource(R.string.about_description)) {
            AppUpdateCard(appUpdateState, onAppUpdateCheck, onAppUpdateDownload, onAppUpdateInstall, isStreaming)
            CoreStatusCard(coreState, onCoreCheck)
        }
    }
}

@Composable
private fun AppUpdateCard(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    isStreaming: Boolean
) {
    SettingCard(stringResource(R.string.app_update_title), stringResource(R.string.app_update_description)) {
        Text(stringResource(R.string.app_update_current, state.currentVersion), style = MaterialTheme.typography.bodyMedium)
        state.latestVersion?.let { latest ->
            SupportingText(stringResource(R.string.app_update_latest, latest))
        }
        when (state.phase) {
            AppUpdatePhase.CHECKING -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                SupportingText(stringResource(R.string.app_update_checking))
            }
            AppUpdatePhase.AVAILABLE -> SupportingText(stringResource(R.string.app_update_available))
            AppUpdatePhase.DOWNLOADING -> {
                LinearProgressIndicator(progress = { (state.progressPercent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                SupportingText(stringResource(R.string.app_update_downloading, state.progressPercent ?: 0))
            }
            AppUpdatePhase.READY -> StatusNotice(stringResource(R.string.app_update_ready), success = true)
            AppUpdatePhase.UP_TO_DATE -> SupportingText(stringResource(R.string.app_update_up_to_date))
            AppUpdatePhase.FAILED -> StatusNotice(stringResource(R.string.app_update_failed), error = true)
            AppUpdatePhase.IDLE -> Unit
        }
        if (isStreaming && state.phase in setOf(AppUpdatePhase.AVAILABLE, AppUpdatePhase.READY)) {
            SupportingText(stringResource(R.string.app_update_streaming))
        }
        val action = when (state.phase) {
            AppUpdatePhase.READY -> onInstall
            AppUpdatePhase.AVAILABLE -> onDownload
            else -> onCheck
        }
        val label = when (state.phase) {
            AppUpdatePhase.READY -> R.string.app_update_install
            AppUpdatePhase.AVAILABLE -> R.string.app_update_download
            AppUpdatePhase.FAILED -> R.string.app_update_retry
            else -> R.string.app_update_check
        }
        val enabled = state.phase != AppUpdatePhase.CHECKING && state.phase != AppUpdatePhase.DOWNLOADING &&
            !(isStreaming && state.phase in setOf(AppUpdatePhase.AVAILABLE, AppUpdatePhase.READY))
        OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
            Text(stringResource(label))
        }
    }
}

@Composable
private fun CoreStatusCard(state: CoreState, onCheck: () -> Unit) {
    SettingCard(stringResource(R.string.core_title), stringResource(R.string.core_description)) {
        state.abi?.let { SupportingText(stringResource(R.string.core_architecture, it)) }
        state.activeVersion?.let { Text(stringResource(R.string.core_version, it), style = MaterialTheme.typography.bodyMedium) }
        when (state.phase) {
            CorePhase.CHECKING -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                SupportingText(stringResource(R.string.core_checking))
            }
            CorePhase.DOWNLOADING -> {
                LinearProgressIndicator(progress = { (state.progressPercent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                SupportingText(stringResource(R.string.core_downloading, state.progressPercent ?: 0))
            }
            CorePhase.FAILED -> StatusNotice(stringResource(R.string.core_download_failed))
            CorePhase.DEFERRED -> SupportingText(stringResource(R.string.core_deferred))
            CorePhase.READY -> if (state.notice == CoreNotice.NONE) SupportingText(stringResource(R.string.core_up_to_date))
        }
        when (state.notice) {
            CoreNotice.OFFLINE -> SupportingText(stringResource(R.string.core_offline))
            CoreNotice.INCOMPATIBLE -> SupportingText(stringResource(R.string.core_incompatible, state.latestVersion ?: ""))
            CoreNotice.UPDATE_READY -> SupportingText(stringResource(R.string.core_update_ready, state.pendingVersion ?: ""))
            else -> Unit
        }
        OutlinedButton(onClick = onCheck, enabled = state.phase != CorePhase.CHECKING && state.phase != CorePhase.DOWNLOADING,
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
            Text(stringResource(if (state.ready) R.string.core_check_updates else R.string.core_retry))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun SettingCard(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            description?.let { SupportingText(it) }
            content()
        }
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SelectionDropdown(label: String, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(value = options.firstOrNull { it.first == value }?.second.orEmpty(), onValueChange = {}, readOnly = true,
            label = { Text(label) }, enabled = enabled, shape = MaterialTheme.shapes.small,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (option, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onChange(option); expanded = false }) }
        }
    }
}

@Composable
private fun <T> ChoiceOptions(label: String, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (option, text) ->
                FilterChip(selected = value == option, onClick = { onChange(option) }, enabled = enabled,
                    label = { Text(text) }, shape = MaterialTheme.shapes.small,
                    leadingIcon = if (value == option) { { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null,
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            SupportingText(description)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun AudioGainControl(gain: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    val label = stringResource(R.string.audio_gain)
    val percent = stringResource(R.string.percent_value, gain)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(percent, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = gain.toFloat(), onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..AudioSettings.MAX_GAIN.toFloat(), steps = 29, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = percent })
        SupportingText(stringResource(R.string.audio_gain_hint))
        TextButton(onClick = { onChange(AudioSettings.DEFAULT_GAIN) }, enabled = enabled && gain != AudioSettings.DEFAULT_GAIN) {
            Text(stringResource(R.string.reset_gain))
        }
    }
}

@Composable
internal fun channelDisplayName(channel: AudioChannel): String =
    stringResource(if (channel == AudioChannel.STEREO) R.string.audio_stereo else R.string.audio_mono)

@Composable
private fun codecDisplayName(codec: String): String = stringResource(if (codec == "H265") R.string.codec_h265_short else R.string.codec_h264_short)

@Composable
fun formatResolutionLabel(size: Size): String {
    val name = when {
        size.width == 3840 && size.height == 2160 -> R.string.resolution_4k
        size.width == 1920 && size.height == 1080 -> R.string.resolution_fhd
        size.width == 1280 && size.height == 720 -> R.string.resolution_hd
        size.width == 854 && size.height == 480 -> R.string.resolution_sd
        size.width == 640 && size.height == 480 -> R.string.resolution_vga
        else -> null
    }
    return if (name != null) stringResource(R.string.resolution_named, size.width, size.height, stringResource(name))
        else stringResource(R.string.resolution_value, size.width, size.height)
}

@Composable
fun LiveTag(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.extraSmall, color = LiveRed, contentColor = Color.White) {
        Text(stringResource(R.string.live_tag), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun StreamStateBadge(state: StreamState) {
    val color = when (state) {
        StreamState.Idle -> MaterialTheme.colorScheme.surfaceVariant
        StreamState.Connecting -> MaterialTheme.colorScheme.secondaryContainer
        is StreamState.Live -> if (state.phase == RelayConnectionPhase.PUBLISHING) LiveRed else MaterialTheme.colorScheme.secondaryContainer
        is StreamState.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val foreground = when (state) {
        is StreamState.Live -> if (state.phase == RelayConnectionPhase.PUBLISHING) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
        is StreamState.Error -> MaterialTheme.colorScheme.onErrorContainer
        StreamState.Connecting -> MaterialTheme.colorScheme.onSecondaryContainer
        StreamState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val animatedColor by animateColorAsState(color, label = "badgeColor")
    Surface(shape = CircleShape, color = animatedColor, contentColor = foreground) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(foreground, CircleShape))
            Text(stringResource(when (state) {
                StreamState.Idle -> R.string.status_idle
                StreamState.Connecting -> R.string.status_connecting
                is StreamState.Live -> state.phase.statusResource()
                is StreamState.Error -> R.string.status_error
            }), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit, onRequestPermission: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_title)) }, actions = {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PermissionRequiredContent(onRequestPermissions = onRequestPermission)
            DiagnosticsExportButton(viewModel, Modifier.fillMaxWidth().heightIn(min = 48.dp))
        }
    }
}

@Composable
fun PermissionRequiredContent(modifier: Modifier = Modifier, onRequestPermissions: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.permission_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRequestPermissions, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.grant_permission))
        }
    }
}
