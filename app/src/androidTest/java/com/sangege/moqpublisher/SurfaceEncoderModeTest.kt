package com.sangege.moqpublisher

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.moq.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SurfaceEncoderModeTest {
    @Test
    fun selectedModeProducesAnIndependentKeyframe() {
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("moqCodec")
        assumeTrue("Pass moqCodec, moqWidth, moqHeight and moqFps for a mode supported by this device", requested != null)
        val codec = when (requested) {
            "H264" -> VideoCodec.H264
            "H265" -> VideoCodec.H265
            else -> error("moqCodec must be H264 or H265")
        }
        val width = checkNotNull(args.getString("moqWidth")).toInt()
        val height = checkNotNull(args.getString("moqHeight")).toInt()
        val fps = checkNotNull(args.getString("moqFps")).toInt()
        val bitrate = args.getString("moqBitrate")?.toLong() ?: 3_000_000L
        val diagnostics = AppDiagnostics(InstrumentationRegistry.getInstrumentation().targetContext)
        val outputReady = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val encoder = SurfaceVideoEncoder.open(width, height, fps, bitrate, codec, diagnostics, { error ->
            failure.compareAndSet(null, error)
            outputReady.countDown()
        }) { frame, headers, _ ->
            if (frame.keyframe && headers.isNotEmpty() && frame.data.isNotEmpty()) outputReady.countDown()
        }
        try {
            drawTestFrames(encoder.inputSurface, width, height, fps)
            assertTrue("No encoded keyframe for $codec ${width}x$height at $fps fps", outputReady.await(8, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("MediaCodec callback failed for $codec", it) }
        } finally {
            encoder.close()
        }
    }

    private fun drawTestFrames(surface: Surface, width: Int, height: Int, fps: Int) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        var context = EGL14.EGL_NO_CONTEXT
        var target = EGL14.EGL_NO_SURFACE
        try {
            val config = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE
            ), 0, config, 0, config.size, count, 0) && count[0] == 1)
            context = EGL14.eglCreateContext(display, config[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            target = EGL14.eglCreateWindowSurface(display, config[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(target != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, target, target, context))
            GLES20.glViewport(0, 0, width, height)
            val start = System.nanoTime()
            repeat(12) { index ->
                GLES20.glClearColor(index / 12f, 0.5f, 0.8f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                check(EGLExt.eglPresentationTimeANDROID(display, target, start + index * 1_000_000_000L / fps))
                check(EGL14.eglSwapBuffers(display, target))
            }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (target != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, target)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
