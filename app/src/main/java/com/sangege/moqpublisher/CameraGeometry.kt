package com.sangege.moqpublisher

import kotlin.math.max
import kotlin.math.sqrt

/** Shared by GPU preview and metering, including center crop and front-camera mirroring. */
internal object CameraGeometry {
    data class Point(val x: Float, val y: Float)

    /**
     * SurfaceTexture can already contain the camera's HAL rotation/mirror. Remove that
     * orientation while retaining its crop and the required GL vertical flip. The declared
     * sensor-to-display rotation is applied once, in textureCoordinates, for both outputs.
     */
    fun normalizeTextureTransform(input: FloatArray): FloatArray {
        require(input.size == 16)
        val sx = sqrt(input[0] * input[0] + input[4] * input[4])
        val sy = sqrt(input[1] * input[1] + input[5] * input[5])
        val cx = input[12] + (input[0] + input[4]) / 2f
        val cy = input[13] + (input[1] + input[5]) / 2f
        return floatArrayOf(sx, 0f, 0f, 0f, 0f, -sy, 0f, 0f, 0f, 0f, 1f, 0f,
            cx - sx / 2f, cy + sy / 2f, 0f, 1f)
    }

    /** GL strip: bottom left, bottom right, top left, top right. Crop before rotation. */
    fun textureCoordinates(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int,
        rotation: Int, mirror: Boolean): FloatArray = listOf(
        0f to targetHeight.toFloat(), targetWidth.toFloat() to targetHeight.toFloat(),
        0f to 0f, targetWidth.toFloat() to 0f
    ).flatMap { (x, y) ->
        val p = meteringPoint(x, y, targetWidth, targetHeight, sourceWidth, sourceHeight, rotation, mirror)
        listOf(p.x, 1f - p.y)
    }.toFloatArray()

    fun meteringPoint(
        x: Float, y: Float, viewWidth: Int, viewHeight: Int,
        sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int, mirror: Boolean
    ): Point {
        require(viewWidth > 0 && viewHeight > 0 && sourceWidth > 0 && sourceHeight > 0)
        val rotated = rotationDegrees % 180 != 0
        val width = if (rotated) sourceHeight else sourceWidth
        val height = if (rotated) sourceWidth else sourceHeight
        val scale = max(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
        var u = ((x - viewWidth / 2f) / scale / width + 0.5f).coerceIn(0f, 1f)
        val v = ((y - viewHeight / 2f) / scale / height + 0.5f).coerceIn(0f, 1f)
        if (mirror) u = 1f - u
        return when ((rotationDegrees % 360 + 360) % 360) {
            90 -> Point(v, 1f - u)
            180 -> Point(1f - u, 1f - v)
            270 -> Point(1f - v, u)
            else -> Point(u, v)
        }
    }
}
