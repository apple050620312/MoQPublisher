package com.sangege.moqpublisher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class ScreenCaptureState(
    val active: Boolean = false,
    val starting: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val error: UiMessage? = null
) {
    val busy: Boolean get() = active || starting
}

internal class ScreenCaptureRequest(val consent: Intent, val maxEdge: Int, val fps: Int)

internal data class ScreenCaptureSize(val width: Int, val height: Int) {
    companion object {
        fun fit(width: Int, height: Int, maxEdge: Int): ScreenCaptureSize {
            require(width > 0 && height > 0 && maxEdge >= 2)
            val limit = maxEdge / 2 * 2
            val scale = minOf(1.0, limit.toDouble() / maxOf(width, height))
            fun aligned(dimension: Int) = (dimension * scale / 2).roundToInt().coerceAtLeast(1) * 2
            return ScreenCaptureSize(aligned(width), aligned(height))
        }
    }
}

/** Main-thread owner of one projection grant and one virtual display. */
internal class ScreenCaptureEngine(
    private val context: Context,
    private val camera: CameraCaptureEngine,
    private val diagnostics: AppDiagnostics,
    private val onEnded: (UiMessage?) -> Unit
) {
    private val manager = context.getSystemService(MediaProjectionManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(ScreenCaptureState())
    val state = mutableState.asStateFlow()
    private var capture: Capture? = null

    private inner class Capture(
        val projection: MediaProjection,
        val request: ScreenCaptureRequest,
        var size: ScreenCaptureSize
    ) : MediaProjection.Callback(), DisplayManager.DisplayListener {
        @Volatile var valid = true
        var display: VirtualDisplay? = null

        override fun onStop() {
            if (capture === this) end("projection_revoked", UiMessage(R.string.screen_capture_revoked))
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0) resize(this, ScreenCaptureSize.fit(width, height, request.maxEdge))
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) resize(this, displaySize(request.maxEdge))
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    fun permissionIntent(): Intent = manager.createScreenCaptureIntent()

    /** Called after the foreground service has entered the mediaProjection type. */
    fun start(request: ScreenCaptureRequest): MediaProjection? {
        if (capture != null) return null
        return try {
            val size = displaySize(request.maxEdge)
            val projection = checkNotNull(manager.getMediaProjection(Activity.RESULT_OK, request.consent))
            val current = Capture(projection, request, size)
            capture = current
            mutableState.value = ScreenCaptureState(starting = true)
            projection.registerCallback(current, handler)
            if (Build.VERSION.SDK_INT < 34) displays.registerDisplayListener(current, handler)
            camera.provideScreen(current, size, request.fps, { current.valid }, { surface ->
                handler.post {
                    if (capture === current) try {
                        current.display = checkNotNull(projection.createVirtualDisplay(
                            "MoQ Publisher screen", size.width, size.height, densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
                        ))
                        markActive(size, request.fps)
                        if (current.size != size) resize(current, current.size, force = true)
                        diagnostics.event("screen_capture_started", mapOf(
                            "width" to size.width, "height" to size.height, "fps" to request.fps
                        ))
                    } catch (error: Exception) { fail(current, error) }
                }
            }, { error -> handler.post { fail(current, error) } })
            handler.postAtTime({
                if (capture === current && state.value.starting) {
                    fail(current, IllegalStateException("Timed out creating the screen input surface"))
                }
            }, current, android.os.SystemClock.uptimeMillis() + 5_000)
            projection
        } catch (error: Exception) {
            diagnostics.error("screen_capture_start_failed", error)
            end("start_failed", UiMessage(R.string.screen_capture_start_failed), notify = false)
            null
        }
    }

    private fun resize(current: Capture, size: ScreenCaptureSize, force: Boolean = false) {
        if (capture !== current || (!force && current.size == size)) return
        current.size = size
        val display = current.display ?: return
        camera.resizeScreen(current, size, onResized = {
            handler.post {
                if (capture === current && current.size == size) try {
                    display.resize(size.width, size.height, densityDpi)
                    markActive(size, current.request.fps)
                } catch (error: Exception) { fail(current, error) }
            }
        }, onError = { error -> handler.post { fail(current, error) } })
    }

    private fun markActive(size: ScreenCaptureSize, fps: Int) {
        mutableState.value = ScreenCaptureState(active = true, width = size.width, height = size.height, fps = fps)
    }

    private fun fail(current: Capture, error: Throwable) {
        if (capture !== current) return
        diagnostics.error("screen_capture_failed", error)
        end("capture_failed", (error as? UiException)?.uiMessage ?: UiMessage(R.string.screen_capture_start_failed))
    }

    fun stop() = end("user")

    fun stopForBroadcastEnd() = end("broadcast_stopped", notify = false)

    private fun end(reason: String, error: UiMessage? = null, notify: Boolean = true) {
        val current = capture
        capture = null
        if (current == null && error == null) {
            return
        }
        if (current != null) {
            current.valid = false
            handler.removeCallbacksAndMessages(current)
            if (Build.VERSION.SDK_INT < 34) cleanup { displays.unregisterDisplayListener(current) }
            cleanup { current.display?.release() }
            cleanup { current.projection.unregisterCallback(current) }
            cleanup { current.projection.stop() }
            camera.releaseScreen(current)
            diagnostics.event("screen_capture_stopped", mapOf("reason" to reason))
        }
        mutableState.value = ScreenCaptureState(error = error)
        if (notify) onEnded(error)
    }

    private fun cleanup(release: () -> Unit) {
        runCatching(release).onFailure { diagnostics.error("screen_capture_cleanup_failed", it) }
    }

    private val densityDpi get() = context.resources.configuration.densityDpi

    @Suppress("DEPRECATION")
    private fun displaySize(maxEdge: Int): ScreenCaptureSize {
        val display = checkNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY))
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return ScreenCaptureSize.fit(metrics.widthPixels, metrics.heightPixels, maxEdge)
    }
}
