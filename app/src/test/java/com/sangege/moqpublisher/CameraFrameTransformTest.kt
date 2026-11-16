package com.sangege.moqpublisher

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class CameraFrameTransformTest {
    @Test fun removesHalRotationAndMirrorWhileRetainingCropAndGlFlip() {
        // Same sensor crop, with the four orthogonal HAL transforms and their mirrors.
        for ((a, b, c, d) in listOf(floatArrayOf(1f, 0f, 0f, -1f), floatArrayOf(0f, 1f, 1f, 0f),
            floatArrayOf(-1f, 0f, 0f, 1f), floatArrayOf(0f, -1f, -1f, 0f))) for (mirror in listOf(1f, -1f)) {
            val input = FloatArray(16)
            input[0] = a * 0.8f * mirror; input[4] = b * 0.8f
            input[1] = c * 0.6f * mirror; input[5] = d * 0.6f
            input[12] = 0.5f - (input[0] + input[4]) / 2
            input[13] = 0.5f - (input[1] + input[5]) / 2
            input[10] = 1f; input[15] = 1f
            val actual = CameraGeometry.normalizeTextureTransform(input)
            assertArrayEquals(floatArrayOf(.8f, 0f, 0f, 0f, 0f, -.6f, 0f, 0f, 0f, 0f, 1f, 0f, .1f, .8f, 0f, 1f), actual, .0001f)
        }
    }

    @Test fun rotationMovesKnownSensorCornersInTheCorrectClockwiseDirection() {
        val expected = mapOf(0 to floatArrayOf(0f, 1f, 1f, 1f), 90 to floatArrayOf(0f, 0f, 0f, 1f),
            180 to floatArrayOf(1f, 0f, 0f, 0f), 270 to floatArrayOf(1f, 1f, 1f, 0f))
        for ((rotation, top) in expected) {
            val uv = CameraGeometry.textureCoordinates(1920, 1080,
                if (rotation % 180 == 0) 1920 else 1080, if (rotation % 180 == 0) 1080 else 1920, rotation, false)
            assertArrayEquals(top, uv.copyOfRange(4, 8), .0001f)
        }
    }

    @Test fun portraitLandscapeAndSquareOutputsPreservePhysicalPixelAspectAtEveryRotation() {
        for ((width, height) in listOf(1080 to 1920, 1920 to 1080, 1080 to 1080, 960 to 2100))
            for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
                val uv = CameraGeometry.textureCoordinates(1920, 1080, width, height, rotation, mirror)
                fun distance(a: Int, b: Int): Float = hypot((uv[a] - uv[b]) * 1920, (uv[a + 1] - uv[b + 1]) * 1080)
                assertEquals("$width x $height rotation $rotation mirror $mirror",
                    distance(0, 2) / width, distance(0, 4) / height, .0001f)
            }
    }

    @Test fun onlyTheFrontPreviewIsMirroredAndEncoderMappingStaysUnmirrored() {
        val plain = CameraGeometry.textureCoordinates(1920, 1080, 1920, 1080, 0, false)
        val mirrored = CameraGeometry.textureCoordinates(1920, 1080, 1920, 1080, 0, true)
        assertEquals(plain[0], mirrored[2], .0001f)
        assertEquals(plain[2], mirrored[0], .0001f)
        assertEquals(plain[1], mirrored[3], .0001f)
    }
}
