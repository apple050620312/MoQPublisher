package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFramePacerTest {
    @Test
    fun regularSensorFramesKeepSixtyFpsDespiteVariableCallbackScheduling() {
        val sensorPacer = VideoFramePacer(60)
        val arrivalPacer = VideoFramePacer(60)
        var sensorAccepted = 0
        var arrivalAccepted = 0
        repeat(600) { frame ->
            val capture = frame * 1_000_000_000L / 60L
            val callbackDelay = if (frame % 3 == 0) 5_000_000L else 0L
            if (sensorPacer.shouldEncode(capture)) sensorAccepted++
            if (arrivalPacer.shouldEncode(capture + callbackDelay)) arrivalAccepted++
        }
        assertEquals(600, sensorAccepted)
        assertTrue("Replay must exercise callback scheduling drops", arrivalAccepted < sensorAccepted)
    }

    @Test
    fun switchingCamerasWithDifferentSensorClockOriginsResumesImmediately() {
        val pacer = VideoFramePacer(60)
        assertTrue(pacer.shouldEncode(5_000_000_000L))
        assertTrue(pacer.shouldEncode(100_000_000L))
        assertFalse(pacer.shouldEncode(101_000_000L))
        assertTrue(pacer.shouldEncode(116_666_667L))
    }

    @Test
    fun sixtyFpsCameraFeedsThirtyFramesPerSecondToTheEncoder() {
        val pacer = VideoFramePacer(30)
        val accepted = (0L until 600L).count {
            pacer.shouldEncode(it * 1_000_000_000L / 60L)
        }
        assertEquals(300, accepted)
    }

    @Test
    fun highRateCameraHonorsAllConfiguredFrameRates() {
        for (fps in listOf(15, 24, 30, 60, 120)) {
            val pacer = VideoFramePacer(fps)
            val accepted = (0L until 1_200L).count {
                pacer.shouldEncode(it * 1_000_000_000L / 120L)
            }
            assertEquals("Target $fps FPS", fps * 10, accepted)
        }
    }

    @Test
    fun smallCameraTimingJitterDoesNotHalveTheFrameRate() {
        val pacer = VideoFramePacer(30)
        val accepted = (0L until 300L).count {
            val jitter = if (it % 2L == 0L) 0L else -1_000_000L
            pacer.shouldEncode(it * 1_000_000_000L / 30L + jitter)
        }
        assertEquals(300, accepted)
    }

    @Test
    fun pauseDoesNotAccumulateACatchUpBurst() {
        val pacer = VideoFramePacer(30)
        assertTrue(pacer.shouldEncode(0L))
        assertTrue(pacer.shouldEncode(5_000_000_000L))
        assertFalse(pacer.shouldEncode(5_001_000_000L))
        assertFalse(pacer.shouldEncode(5_016_666_666L))
        assertTrue(pacer.shouldEncode(5_033_333_333L))
    }

    @Test
    fun longSensorPauseReanchorsWithoutAStaleClockDelay() {
        val pacer = VideoFramePacer(30)
        assertTrue(pacer.shouldEncode(0L))
        assertTrue(pacer.shouldEncode(3_000_000_000L))
        assertFalse(pacer.shouldEncode(3_001_000_000L))
        assertTrue(pacer.shouldEncode(3_033_333_333L))
    }

    @Test
    fun slowerCameraIsNotDelayedOrDuplicated() {
        val pacer = VideoFramePacer(60)
        repeat(300) {
            assertTrue(pacer.shouldEncode(it * 1_000_000_000L / 30L))
        }
    }

    @Test
    fun acceptsANegativeMonotonicClockOrigin() {
        val pacer = VideoFramePacer(30)
        assertTrue(pacer.shouldEncode(-5_000_000_000L))
        assertFalse(pacer.shouldEncode(-4_999_000_000L))
        assertTrue(pacer.shouldEncode(-4_966_666_667L))
    }
}
