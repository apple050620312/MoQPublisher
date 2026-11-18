package com.sangege.moqpublisher

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Camera ownership survives Activity destruction; only the display surface belongs to the UI. */
internal class CameraCaptureEngine(
    context: Context,
    private val controls: CameraControlHandle,
    private val streamer: MoqStreamer,
    private val diagnostics: AppDiagnostics,
    private val onError: (Throwable) -> Unit
) : LifecycleOwner {
    private val context = context.applicationContext
    override val lifecycle = LifecycleRegistry(this)
    private val executor = ContextCompat.getMainExecutor(context)
    private val mutableCapture = MutableStateFlow<CameraCaptureStatus?>(null)
    val captureStatus = mutableCapture.asStateFlow()
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var uiActive = false
    private var streaming = false
    private var screenSource = false
    private var generation = 0
    private var front = false
    private var size = Size(1280, 720)
    private var fps = 30
    private var videoSettings = CameraVideoSettings()
    private val lenses = CameraLensRepository(context)
    private var videoModeChange: LiveVideoModeChange? = null
    private var viewWidth = 1
    private var viewHeight = 1
    private var sourceWidth = 1280
    private var sourceHeight = 720
    private var displayRotation = Surface.ROTATION_0
    private var frozenRotation = 0
    private var streamDisplayRotation = Surface.ROTATION_0
    private var actuallyFront = false
    private val renderer = CameraSurfaceRenderer(
        streamer::prepareVideoSurface, streamer::onSurfaceFrame,
        { error -> executor.execute { diagnostics.error("camera_surface_error", error); onError(error) } },
        { elapsed -> diagnostics.metrics.timing(StreamMetrics.Stage.GPU_SUBMIT, elapsed) },
        { fields -> diagnostics.event("camera_transform", fields) }
    )

    init { lifecycle.currentState = Lifecycle.State.CREATED }

    fun configure(useFront: Boolean, mode: VideoMode, change: LiveVideoModeChange? = null) {
        if (front == useFront && size == mode.size && fps == mode.fps && videoSettings == mode.camera && change == null) return
        front = useFront; size = mode.size; fps = mode.fps
        videoSettings = mode.camera
        videoModeChange = change
        if (preview != null || uiActive || streaming) bind()
    }

    fun setUiActive(active: Boolean) {
        if (uiActive == active) return
        uiActive = active
        updatePresentation()
        updateLifecycle()
    }

    fun setStreaming(active: Boolean) {
        if (streaming == active) return
        streaming = active
        if (active) { streamDisplayRotation = displayRotation; frozenRotation = rotation() }
        renderer.setEncoding(active, frozenRotation)
        updateLifecycle()
    }

    fun setScreenSource(enabled: Boolean) {
        if (screenSource == enabled) return
        screenSource = enabled
        updateLifecycle()
    }

    fun provideScreen(
        token: Any, size: ScreenCaptureSize, fps: Int, isValid: () -> Boolean,
        onSurface: (Surface) -> Unit, onError: (Throwable) -> Unit
    ) = renderer.provideScreen(token, size.width, size.height, fps, isValid, onSurface, onError)

    fun resizeScreen(token: Any, size: ScreenCaptureSize, onResized: () -> Unit, onError: (Throwable) -> Unit) =
        renderer.resizeScreen(token, size.width, size.height, onResized, onError)

    fun releaseScreen(token: Any) = renderer.releaseScreen(token)

    private val cameraNeeded get() = !screenSource && (uiActive || streaming)

    fun attachPreview(texture: SurfaceTexture, width: Int, height: Int, rotation: Int) {
        viewWidth = width.coerceAtLeast(1); viewHeight = height.coerceAtLeast(1)
        displayRotation = rotation
        renderer.attachPreview(texture, viewWidth, viewHeight)
        updatePresentation()
    }

    fun detachPreview(texture: SurfaceTexture) = renderer.detachPreview(texture)
    fun detachEncoder() = renderer.detachEncoder()

    /** Rebinds the CameraX surface while the application-owned stream remains live. */
    fun recover() {
        executor.execute {
            if (!cameraNeeded) return@execute
            generation++
            removeCameraObserver()
            val oldPreview = preview
            preview = null
            camera = null
            controls.detach()
            oldPreview?.let { provider?.unbind(it) }
            mutableCapture.value = null
            diagnostics.event("camera_recovery_rebind", mapOf("streaming" to streaming, "generation" to generation))
            lifecycle.currentState = Lifecycle.State.CREATED
            lifecycle.currentState = Lifecycle.State.RESUMED
            bind()
        }
    }

    fun updateDisplayRotation(rotation: Int) {
        if (displayRotation == rotation) return
        displayRotation = rotation
        updatePresentation()
    }

    private fun rotation(): Int = camera?.cameraInfo?.getSensorRotationDegrees(displayRotation) ?: 0

    private fun updatePresentation() {
        renderer.setPresentation(rotation(), actuallyFront, uiActive)
    }

    private fun updateLifecycle() {
        if (cameraNeeded) {
            lifecycle.currentState = Lifecycle.State.RESUMED
            if (preview == null) bind()
        } else {
            generation++
            removeCameraObserver()
            preview?.let { provider?.unbind(it) }
            preview = null; camera = null; controls.detach(); mutableCapture.value = null
            lifecycle.currentState = Lifecycle.State.CREATED
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalMirrorMode::class, ExperimentalCamera2Interop::class)
    private fun bind() {
        if (!cameraNeeded) return
        val ticket = ++generation
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (ticket != generation || !cameraNeeded) return@addListener
            try {
                val cameraProvider = future.get()
                removeCameraObserver()
                preview?.let { cameraProvider.unbind(it) }
                controls.detach()
                val settings = videoSettings
                val lens = settings.lensId?.let { id -> lenses.list().firstOrNull { it.id == id } }
                val requested = if (lens != null) CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == lens.logicalId }
                }.build() else if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val defaultSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val fallback = if (cameraProvider.hasCamera(defaultSelector)) defaultSelector
                    else if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val useRequested = cameraProvider.hasCamera(requested)
                if (settings.lensId != null && (lens == null || !useRequested) &&
                    videoModeChange?.result?.value == LiveVideoModeChange.Result.PENDING) {
                    videoModeChange?.finish(false)
                    return@addListener
                }
                val selector = if (useRequested) requested else fallback
                actuallyFront = if (useRequested) lens?.front ?: front else fallback === CameraSelector.DEFAULT_FRONT_CAMERA
                val info = cameraProvider.getCameraInfo(selector)
                val requestedSize = size
                val requestedFps = fps
                val modeChange = videoModeChange
                val ratio = requestedSize.width.toDouble() / requestedSize.height
                val resolutions = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(
                        if (kotlin.math.abs(ratio - 16.0 / 9) < kotlin.math.abs(ratio - 4.0 / 3)) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3,
                        AspectRatioStrategy.FALLBACK_RULE_AUTO
                    ))
                    .setResolutionStrategy(ResolutionStrategy(requestedSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .setResolutionFilter { candidates, _ ->
                        val allowed = CameraCapturePolicy.boundedResolutions(
                            candidates.map { CameraCapturePolicy.Resolution(it.width, it.height) },
                            CameraCapturePolicy.Resolution(requestedSize.width, requestedSize.height)
                        ).toSet()
                        candidates.filter { CameraCapturePolicy.Resolution(it.width, it.height) in allowed }
                    }.build()
                // Keep encoded video unmirrored; the GPU mirrors the front preview only.
                val previewBuilder = Preview.Builder().setMirrorMode(androidx.camera.core.MirrorMode.MIRROR_MODE_OFF)
                    .setTargetRotation(Surface.ROTATION_0).setResolutionSelector(resolutions)
                val interop = Camera2Interop.Extender(previewBuilder)
                if (Build.VERSION.SDK_INT >= 28 && useRequested && lens?.physicalId != null) {
                    interop.setPhysicalCameraId(lens.physicalId)
                }
                val stabilizationModes = Camera2CameraInfo.from(info).getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                )
                val stabilized = settings.stabilize && (lens?.stabilizesVideo ?:
                    (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON in (stabilizationModes ?: intArrayOf())))
                if (stabilized) interop.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
                val useCase = previewBuilder.build()
                useCase.setSurfaceProvider(executor) { request ->
                    if (ticket != generation) {
                        request.willNotProvideSurface()
                        return@setSurfaceProvider
                    }
                    sourceWidth = request.resolution.width; sourceHeight = request.resolution.height
                    renderer.provide(request, requestedFps, modeChange, settings.direction, settings.mirrorFront && actuallyFront)
                }
                val sessionRates = runCatching {
                    info.getSupportedFrameRateRanges(SessionConfig.Builder(useCase).build())
                }.getOrDefault(emptySet())
                val rate = CameraCapturePolicy.frameRate(
                    sessionRates.ifEmpty { info.supportedFrameRateRanges }.map { CameraCapturePolicy.FrameRate(it.lower, it.upper) }, requestedFps
                )
                val session = SessionConfig.Builder(useCase).setFrameRateRange(Range(rate.lower, rate.upper)).build()
                val bound = cameraProvider.bindToLifecycle(this, selector, session)
                provider = cameraProvider; preview = useCase; camera = bound
                cameraObserver = Observer<CameraState> { state ->
                    if (camera === bound && state.error?.code in setOf(CameraState.ERROR_STREAM_CONFIG,
                            CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_CAMERA_FATAL_ERROR)) {
                        val error = IllegalStateException("Camera state error: ${state.error?.code}")
                        when (modeChange?.result?.value) {
                            LiveVideoModeChange.Result.PENDING -> {
                                diagnostics.error("camera_mode_change_failed", error)
                                modeChange.finish(false)
                            }
                            LiveVideoModeChange.Result.FAILED -> Unit
                            else -> if (streaming) streamer.cameraFailed(error) else onError(error)
                        }
                    }
                }.also { bound.cameraInfo.cameraState.observe(this, it) }
                controls.attach(bound, this, { x, y ->
                    val point = CameraGeometry.meteringPoint(x, y, viewWidth, viewHeight, sourceWidth, sourceHeight, rotation(), actuallyFront)
                    SurfaceOrientedMeteringPointFactory(1f, 1f, useCase).createPoint(point.x, point.y)
                }, { viewWidth / 2f to viewHeight / 2f }, executor)
                val actual = useCase.resolutionInfo?.resolution
                mutableCapture.value = CameraCaptureStatus(actual?.width ?: 0, actual?.height ?: 0, fps, rate.lower, rate.upper)
                updatePresentation()
                // A camera switch may change sensor orientation; keep the display orientation captured at stream start.
                if (streaming) renderer.setEncoding(true, bound.cameraInfo.getSensorRotationDegrees(streamDisplayRotation))
                diagnostics.event("camera_bound", mapOf("front" to actuallyFront, "resolution" to actual,
                    "requested_fps" to fps, "fps" to "$rate", "path" to "gpu_surface", "owner" to "broadcast_session",
                    "lens" to lens?.id, "stabilization_requested" to stabilized, "output_direction" to settings.direction))
            } catch (error: Throwable) {
                preview = null; camera = null; controls.detach(); mutableCapture.value = null
                diagnostics.error("camera_bind_failed", error)
                val pendingChange = videoModeChange
                when (pendingChange?.result?.value) {
                    LiveVideoModeChange.Result.PENDING -> pendingChange.finish(false)
                    LiveVideoModeChange.Result.FAILED -> Unit
                    else -> if (videoSettings.lensId != null || videoSettings.stabilize) {
                        videoSettings = videoSettings.copy(lensId = null, stabilize = false)
                        diagnostics.event("camera_mode_fallback")
                        bind()
                    } else if (streaming) streamer.cameraFailed(error) else onError(error)
                }
            }
        }, executor)
    }

    private fun removeCameraObserver() {
        cameraObserver?.let { camera?.cameraInfo?.cameraState?.removeObserver(it) }
        cameraObserver = null
    }
}
