package com.sangege.moqpublisher

import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.camera.core.ZoomState
import androidx.camera.core.TorchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import kotlin.math.roundToInt

data class CameraControlState(
    val attached: Boolean = false,
    val hasFlashUnit: Boolean = false,
    val torchEnabled: Boolean = false,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val exposureSupported: Boolean = false,
    val exposureIndex: Int = 0,
    val minExposureIndex: Int = 0,
    val maxExposureIndex: Int = 0,
    val exposureStep: Float = 0f
)

/** Lifecycle-safe bridge between CameraX's bound Camera and Compose camera controls. */
class CameraControlHandle(private val onExposureSaved: (Float) -> Unit = {}) {
    private val _state = MutableStateFlow(CameraControlState())
    val state: StateFlow<CameraControlState> = _state.asStateFlow()

    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var torchObserver: Observer<Int>? = null
    private var meter: ((Float, Float) -> MeteringPoint)? = null
    private var center: (() -> Pair<Float, Float>)? = null
    private var executor: Executor? = null
    private var requestedExposureEv = 0f

    @Synchronized
    fun attach(boundCamera: Camera, view: PreviewView, owner: LifecycleOwner) {
        attach(boundCamera, owner, { x, y -> view.meteringPointFactory.createPoint(x, y) },
            { view.width / 2f to view.height / 2f }, view.context.mainExecutorCompat())
        previewView = view
    }

    @Synchronized
    internal fun attach(
        boundCamera: Camera, owner: LifecycleOwner,
        metering: (Float, Float) -> MeteringPoint, centerPoint: () -> Pair<Float, Float>, mainExecutor: Executor
    ) {
        detach()
        camera = boundCamera
        meter = metering
        center = centerPoint
        executor = mainExecutor
        lifecycleOwner = owner
        val zoomLiveData = boundCamera.cameraInfo.zoomState
        val torchLiveData = boundCamera.cameraInfo.torchState
        zoomObserver = Observer { zoom ->
            _state.value = _state.value.copy(
                attached = true,
                zoomRatio = zoom.zoomRatio,
                minZoomRatio = zoom.minZoomRatio,
                maxZoomRatio = zoom.maxZoomRatio
            )
        }
        torchObserver = Observer { torch ->
            _state.value = _state.value.copy(attached = true, torchEnabled = torch == TorchState.ON)
        }
        zoomLiveData.observe(owner, zoomObserver!!)
        torchLiveData.observe(owner, torchObserver!!)
        val exposure = boundCamera.cameraInfo.exposureState
        _state.value = _state.value.copy(
            attached = true, hasFlashUnit = boundCamera.cameraInfo.hasFlashUnit(),
            exposureSupported = exposure.isExposureCompensationSupported,
            exposureIndex = exposure.exposureCompensationIndex,
            minExposureIndex = exposure.exposureCompensationRange.lower,
            maxExposureIndex = exposure.exposureCompensationRange.upper,
            exposureStep = exposure.exposureCompensationStep.toFloat()
        )
        if (exposure.isExposureCompensationSupported && exposure.exposureCompensationStep.toFloat() > 0f)
            setExposureIndex((requestedExposureEv / exposure.exposureCompensationStep.toFloat()).roundToInt())
    }

    @Synchronized
    fun detach() {
        zoomObserver?.let { observer -> lifecycleOwner?.let { camera?.cameraInfo?.zoomState?.removeObserver(observer) } }
        torchObserver?.let { observer -> lifecycleOwner?.let { camera?.cameraInfo?.torchState?.removeObserver(observer) } }
        zoomObserver = null
        torchObserver = null
        camera = null
        previewView = null
        lifecycleOwner = null
        meter = null
        center = null
        executor = null
        _state.value = CameraControlState()
    }

    fun setZoomRatio(ratio: Float) {
        val current = _state.value
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(current.minZoomRatio, current.maxZoomRatio))
    }

    fun zoomBy(delta: Float) {
        val current = _state.value
        setZoomRatio((current.zoomRatio + delta).coerceIn(current.minZoomRatio, current.maxZoomRatio))
    }

    fun resetZoom() = setZoomRatio(1f)

    fun toggleTorch() {
        val current = _state.value
        if (current.hasFlashUnit) camera?.cameraControl?.enableTorch(!current.torchEnabled)
    }

    fun focusAt(x: Float, y: Float) {
        val boundCamera = camera ?: return
        val point = meter?.invoke(x, y) ?: return
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        boundCamera.cameraControl.startFocusAndMetering(action)
    }

    fun focusCenter() {
        val (x, y) = center?.invoke() ?: return
        focusAt(x, y)
    }

    internal fun restoreExposure(ev: Float) { requestedExposureEv = ev.takeIf { it.isFinite() } ?: 0f }

    fun setExposureIndex(index: Int) {
        val bound = camera ?: return
        val current = _state.value
        if (!current.exposureSupported) return
        val selected = index.coerceIn(current.minExposureIndex, current.maxExposureIndex)
        requestedExposureEv = selected * current.exposureStep
        val result = bound.cameraControl.setExposureCompensationIndex(selected)
        result.addListener({
            if (camera === bound) runCatching { result.get() }.onSuccess { actual ->
                _state.value = _state.value.copy(exposureIndex = actual)
                onExposureSaved(actual * current.exposureStep)
            }
        }, executor ?: return)
    }
}

private fun android.content.Context.mainExecutorCompat(): Executor = androidx.core.content.ContextCompat.getMainExecutor(this)
