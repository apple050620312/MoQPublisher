package com.sangege.moqpublisher

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenCaptureState(
    val active: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val error: String? = null
)

/** MediaProjection source that feeds the existing surface encoder. */
internal class ScreenCaptureEngine(
    context: Context,
    private val streamer: MoqStreamer,
    private val diagnostics: AppDiagnostics
) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = context.getSystemService(MediaProjectionManager::class.java)
    private val mutableState = MutableStateFlow(ScreenCaptureState())
    val state = mutableState.asStateFlow()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Handler(Looper.getMainLooper()).post { stop("projection_stopped") }
        }
    }

    fun permissionIntent(): Intent = manager.createScreenCaptureIntent()

    fun start(resultCode: Int, data: Intent, width: Int, height: Int, fps: Int, densityDpi: Int): Boolean {
        if (state.value.active || !streamer.streamState.value.isActive()) return false
        val normalizedWidth = width.coerceIn(320, 3840).let { it - it % 2 }
        val normalizedHeight = height.coerceIn(240, 3840).let { it - it % 2 }
        return runCatching {
            val mediaProjection = checkNotNull(manager.getMediaProjection(resultCode, data))
            val input = checkNotNull(streamer.prepareScreenSurface(normalizedWidth, normalizedHeight, fps))
            mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
            val virtualDisplay = mediaProjection.createVirtualDisplay(
                "MoQ Publisher screen", normalizedWidth, normalizedHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null
            )
            projection = mediaProjection
            display = virtualDisplay
            surface = input
            mutableState.value = ScreenCaptureState(true, normalizedWidth, normalizedHeight, fps)
            diagnostics.event("screen_capture_started", mapOf("width" to normalizedWidth, "height" to normalizedHeight, "fps" to fps))
            true
        }.getOrElse { error ->
            diagnostics.error("screen_capture_start_failed", error)
            stop("start_failed")
            false
        }
    }

    fun stop(reason: String = "user") {
        display?.release()
        display = null
        surface = null
        projection?.unregisterCallback(callback)
        projection?.stop()
        projection = null
        streamer.releaseScreenSurface()
        if (mutableState.value.active) diagnostics.event("screen_capture_stopped", mapOf("reason" to reason))
        mutableState.value = ScreenCaptureState()
    }

    override fun close() = stop("closed")
}
