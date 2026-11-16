package com.sangege.moqpublisher

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One persistent camera input, independent preview and codec outputs, no CPU pixel readback. */
internal class CameraSurfaceRenderer(
    private val encoderSurface: (Int, Int) -> Surface?,
    private val frameTimestamp: (Long) -> Long?,
    private val onFailure: (Throwable) -> Unit,
    private val onSubmitted: (Long) -> Unit = {},
    private val onTransform: (Map<String, Any?>) -> Unit = {}
) {
    private val thread = HandlerThread("MoqCameraGpu", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var input: Input? = null
    private var preview: Output? = null
    private var previewTexture: SurfaceTexture? = null
    private var codec: Output? = null
    private var encoding = false
    private var previewVisible = false
    private var previewRotation = 0
    private var encodeRotation = 0
    private var mirrorPreview = false
    private var lastPreviewNs = 0L
    private val textureMatrix = FloatArray(16)
    private val geometryMatrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val coordinates = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
    private data class Input(val texture: SurfaceTexture, val surface: Surface, val id: Int, val width: Int, val height: Int,
        var lastTimestamp: Long = -1, var transformReported: Boolean = false)
    private data class Output(val surface: Surface, val egl: EGLSurface, var width: Int, var height: Int, val ownsSurface: Boolean) {
        var mapping: FloatArray? = null
        var mappedWidth = 0; var mappedHeight = 0; var sourceWidth = 0; var sourceHeight = 0
        var rotation = -1; var mirror = false
    }

    fun provide(request: SurfaceRequest) { handler.post {
        try {
            initialize()
            releaseCodec()
            makeCurrent(pbuffer)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val size = request.resolution
            val texture = SurfaceTexture(ids[0]).apply { setDefaultBufferSize(size.width, size.height) }
            val next = Input(texture, Surface(texture), ids[0], size.width, size.height)
            input = next
            lastPreviewNs = 0L
            texture.setOnFrameAvailableListener({ render(next) }, handler)
            request.provideSurface(next.surface, executor) {
                if (input === next) { input = null; releaseCodec() }
                next.texture.setOnFrameAvailableListener(null)
                next.surface.release(); next.texture.release()
                makeCurrent(pbuffer)
                GLES20.glDeleteTextures(1, intArrayOf(next.id), 0)
            }
        } catch (error: Throwable) { request.willNotProvideSurface(); onFailure(error) }
    } }

    fun attachPreview(texture: SurfaceTexture, width: Int, height: Int) { handler.post {
        try {
            initialize()
            if (previewTexture !== texture) {
                releaseOutput(preview)
                previewTexture = texture
                preview = output(Surface(texture), width, height, ownsSurface = true)
            } else preview?.let { it.width = width; it.height = height }
        } catch (error: Throwable) { android.util.Log.w("MoqCameraGpu", "Display surface unavailable", error) }
    } }

    fun detachPreview(texture: SurfaceTexture) { handler.post {
        if (previewTexture === texture) {
            releaseOutput(preview); preview = null; previewTexture = null
        }
        texture.release()
    } }

    fun setPresentation(rotation: Int, mirror: Boolean, visible: Boolean) { handler.post {
        previewRotation = rotation; mirrorPreview = mirror; previewVisible = visible
    } }

    fun setEncoding(enabled: Boolean, rotation: Int) { handler.post {
        if (encoding != enabled || encodeRotation != rotation) releaseCodec()
        encoding = enabled; encodeRotation = rotation
    } }

    fun detachEncoder() {
        if (android.os.Looper.myLooper() === thread.looper) { releaseCodec(); return }
        val finished = CountDownLatch(1)
        if (handler.post { try { runCatching { releaseCodec() } } finally { finished.countDown() } }) finished.await(2, TimeUnit.SECONDS)
    }

    private fun render(source: Input) {
        if (input !== source) return
        try {
            makeCurrent(pbuffer)
            source.texture.updateTexImage()
            val timestamp = source.texture.timestamp
            if (timestamp == source.lastTimestamp) return
            source.lastTimestamp = timestamp
            source.texture.getTransformMatrix(textureMatrix)
            val normalized = CameraGeometry.normalizeTextureTransform(textureMatrix)
            if (!source.transformReported) {
                source.transformReported = true
                onTransform(mapOf("input" to "${source.width}x${source.height}", "hal_texture" to textureMatrix.toList(),
                    "normalized_texture" to normalized.toList()))
            }
            normalized.copyInto(textureMatrix)
            if (encoding) {
                val rotated = encodeRotation % 180 != 0
                val width = if (rotated) source.height else source.width
                val height = if (rotated) source.width else source.height
                // A network handover can replace the encoder without replacing the camera input.
                val surface = encoderSurface(width, height)
                if (codec?.surface !== surface) {
                    releaseCodec()
                    surface?.let { codec = output(it, width, height, false) }
                }
                val target = codec
                if (target != null) frameTimestamp(timestamp)?.let { pts ->
                    val submittedAt = System.nanoTime()
                    draw(source, target, encodeRotation, false)
                    check(EGLExt.eglPresentationTimeANDROID(display, target.egl, pts))
                    check(EGL14.eglSwapBuffers(display, target.egl)) { "Encoder surface swap failed" }
                    onSubmitted(System.nanoTime() - submittedAt)
                }
            }
            if (previewVisible && (timestamp < lastPreviewNs || timestamp - lastPreviewNs >= 30_000_000L)) preview?.let { target ->
                draw(source, target, previewRotation, mirrorPreview)
                if (!EGL14.eglSwapBuffers(display, target.egl)) {
                    // A destroyed Activity surface must not interrupt the independent broadcast output.
                    releaseOutput(preview); preview = null; previewTexture = null
                }
                lastPreviewNs = timestamp
            }
        } catch (error: Throwable) { onFailure(error) }
    }

    private fun draw(source: Input, target: Output, rotation: Int, mirror: Boolean) {
        makeCurrent(target.egl)
        GLES20.glViewport(0, 0, target.width, target.height)
        // Rotate/crop UVs in image coordinates, never the already transformed GL quad.
        // Using one mapping for display, encoding and metering keeps aspect and direction aligned.
        if (target.mapping == null || target.mappedWidth != target.width || target.mappedHeight != target.height ||
            target.sourceWidth != source.width || target.sourceHeight != source.height || target.rotation != rotation || target.mirror != mirror) {
            target.mapping = CameraGeometry.textureCoordinates(source.width, source.height, target.width, target.height, rotation, mirror)
            target.mappedWidth = target.width; target.mappedHeight = target.height
            target.sourceWidth = source.width; target.sourceHeight = source.height
            target.rotation = rotation; target.mirror = mirror
            onTransform(mapOf("output" to if (target.ownsSurface) "preview" else "encoder",
                "source" to "${source.width}x${source.height}", "target" to "${target.width}x${target.height}",
                "rotation" to rotation, "mirror" to mirror, "uv" to target.mapping?.toList()))
        }
        coordinates.position(0)
        coordinates.put(checkNotNull(target.mapping)).position(0)
        Matrix.setIdentityM(geometryMatrix, 0)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, source.id)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "geometry"), 1, false, geometryMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, textureMatrix, 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        val coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun initialize() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val choices = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            0x3142, 1, EGL14.EGL_NONE
        ), 0, choices, 0, 1, count, 0) && count[0] > 0)
        config = choices[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
        program = GLES20.glCreateProgram()
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val ok = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }
            GLES20.glAttachShader(program, id)
            return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 position; attribute vec4 coordinate; uniform mat4 geometry; uniform mat4 transform; varying vec2 uv; void main(){gl_Position=geometry*position; uv=(transform*coordinate).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 uv; void main(){gl_FragColor=texture2D(image,uv);}")
        GLES20.glLinkProgram(program)
        val ok = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
    }

    private fun output(surface: Surface, width: Int, height: Int, ownsSurface: Boolean): Output {
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(egl != EGL14.EGL_NO_SURFACE) { "Cannot create EGL output" }
        makeCurrent(egl); EGL14.eglSwapInterval(display, 0)
        return Output(surface, egl, width.coerceAtLeast(1), height.coerceAtLeast(1), ownsSurface)
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Cannot activate camera EGL context" }
    }

    private fun releaseOutput(output: Output?) {
        if (output == null) return
        makeCurrent(pbuffer)
        EGL14.eglDestroySurface(display, output.egl)
        if (output.ownsSurface) output.surface.release()
    }
    private fun releaseCodec() { releaseOutput(codec); codec = null }
}
