package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraGeometryTest {
    private fun point(x: Float, y: Float, rotation: Int = 0, mirror: Boolean = false) =
        CameraGeometry.meteringPoint(x, y, 1920, 1080, if (rotation % 180 == 0) 1920 else 1080,
            if (rotation % 180 == 0) 1080 else 1920, rotation, mirror)

    @Test fun centerStaysCenteredForEveryRotationAndMirror() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val p = point(960f, 540f, rotation, mirror)
            assertEquals(0.5f, p.x, 0.0001f); assertEquals(0.5f, p.y, 0.0001f)
        }
    }

    @Test fun landscapeCornersMapToTheCameraPlaneAfterRotation() {
        assertEquals(CameraGeometry.Point(0f, 0f), point(0f, 0f))
        assertEquals(CameraGeometry.Point(0f, 1f), point(0f, 0f, 90))
        assertEquals(CameraGeometry.Point(1f, 1f), point(0f, 0f, 180))
        assertEquals(CameraGeometry.Point(1f, 0f), point(0f, 0f, 270))
    }

    @Test fun frontPreviewMirrorsDisplayBeforeUndoingCameraRotation() {
        assertEquals(CameraGeometry.Point(1f, 0f), point(0f, 0f, mirror = true))
        assertEquals(CameraGeometry.Point(0f, 0f), point(0f, 0f, 90, true))
    }

    @Test fun centerCropDoesNotMeterAtTheInvisibleSensorEdge() {
        val p = CameraGeometry.meteringPoint(0f, 500f, 1000, 1000, 1920, 1080, 0, false)
        assertEquals(0.21875f, p.x, 0.0001f); assertEquals(0.5f, p.y, 0.0001f)
    }
}
