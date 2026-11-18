package com.sangege.moqpublisher

import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Owned by the existing rendering thread. The overlay uses premultiplied alpha. */
internal class OverlayTexture {
    private val uv = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
        position(0)
    }
    private val names = IntArray(1)
    private var program = 0
    private var position = 0
    private var coordinate = 0
    private var image = 0
    private var alpha = 0
    private var texture = 0
    private var width = 0
    private var height = 0
    private var settings = OverlaySettings()
    private var lastFrame: FrameViewport? = null
    private var placement: FrameViewport? = null
    val visible get() = texture != 0

    fun upload(frame: OverlayFrame) {
        val bitmap = frame.bitmap
        if (bitmap == null) {
            clear()
            return
        }
        if (texture == 0) {
            GLES20.glGenTextures(1, names, 0)
            texture = names[0]
            check(texture != 0) { "Could not allocate overlay texture" }
        }
        val target = GLES20.GL_TEXTURE_2D
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (width == bitmap.width && height == bitmap.height) {
            GLUtils.texSubImage2D(target, 0, 0, 0, bitmap)
        } else {
            GLUtils.texImage2D(target, 0, bitmap, 0)
        }
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Could not upload overlay texture" }
        if (width != bitmap.width || height != bitmap.height || settings != frame.settings) {
            width = bitmap.width
            height = bitmap.height
            settings = frame.settings
            lastFrame = null
        }
        if (program == 0) initialize()
    }

    fun draw(vertices: FloatBuffer, frame: FrameViewport) {
        if (!visible) return
        if (frame != lastFrame) {
            placement = settings.placement(frame, width, height)
            lastFrame = frame
        }
        val bounds = checkNotNull(placement)
        GLES20.glViewport(bounds.x, bounds.y, bounds.width, bounds.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(image, 0)
        GLES20.glUniform1f(alpha, settings.opacity / 100f)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun clear() {
        if (texture == 0) return
        names[0] = texture
        GLES20.glDeleteTextures(1, names, 0)
        texture = 0
        width = 0
        height = 0
        lastFrame = null
        placement = null
    }

    private fun initialize() {
        program = linkRenderProgram(
            """
            attribute vec4 position;
            attribute vec2 coordinate;
            varying vec2 uv;
            void main() { gl_Position = position; uv = coordinate; }
            """.trimIndent(),
            """
            precision mediump float;
            uniform sampler2D image;
            uniform float alpha;
            varying vec2 uv;
            void main() { gl_FragColor = texture2D(image, uv) * alpha; }
            """.trimIndent()
        )
        position = GLES20.glGetAttribLocation(program, "position")
        coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        image = GLES20.glGetUniformLocation(program, "image")
        alpha = GLES20.glGetUniformLocation(program, "alpha")
    }
}

internal fun linkRenderProgram(vertexSource: String, fragmentSource: String): Int {
    val status = IntArray(1)
    fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            error(log)
        }
        return id
    }
    val vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource)
    try {
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        try {
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error(log)
            }
            return program
        } finally {
            GLES20.glDeleteShader(fragment)
        }
    } finally {
        GLES20.glDeleteShader(vertex)
    }
}
