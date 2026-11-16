package com.sangege.moqpublisher

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** The UI owns a display surface only. Leaving the screen never disposes a live camera session. */
@Composable
internal fun CameraPreview(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalView.current.context
    val owner = LocalLifecycleOwner.current
    val engine = viewModel.cameraEngine
    val controls = viewModel.cameraControls
    val view = remember(context, engine) {
        TextureView(context).apply {
            isOpaque = true
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private fun attach(texture: SurfaceTexture, width: Int, height: Int) =
                    engine.attachPreview(texture, width, height, display?.rotation ?: Surface.ROTATION_0)
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = attach(texture, width, height)
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = attach(texture, width, height)
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { engine.detachPreview(texture); return false }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
            val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    controls.setZoomRatio(controls.state.value.zoomRatio * detector.scaleFactor)
                    return true
                }
            })
            val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent) = true
                override fun onSingleTapConfirmed(event: MotionEvent): Boolean { controls.focusAt(event.x, event.y); return true }
                override fun onDoubleTap(event: MotionEvent): Boolean { controls.resetZoom(); return true }
            })
            setOnTouchListener { _, event ->
                scale.onTouchEvent(event); gesture.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                true
            }
        }
    }
    DisposableEffect(owner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> (view.context.applicationContext as MoqApplication).session.setUiActive(true)
                Lifecycle.Event.ON_STOP -> (view.context.applicationContext as MoqApplication).session.setUiActive(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        (context.applicationContext as MoqApplication).session.setUiActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            (context.applicationContext as MoqApplication).session.setUiActive(false)
        }
    }
    DisposableEffect(view, engine) {
        val manager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                view.display?.takeIf { it.displayId == displayId }?.let { engine.updateDisplayRotation(it.rotation) }
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    AndroidView(factory = { view }, modifier = modifier, update = {
        if (it.isAvailable) it.surfaceTexture?.let { texture ->
            engine.attachPreview(texture, it.width, it.height, it.display?.rotation ?: Surface.ROTATION_0)
        }
    })
}
