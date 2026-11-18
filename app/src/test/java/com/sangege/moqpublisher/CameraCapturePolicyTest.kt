package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class CameraCapturePolicyTest {
    @Test fun outputOrientationUsesTheRequestedAxesAndPreservesFixedRotation() {
        assertEquals(90, CameraCapturePolicy.outputRotation(1280, 720, 90, VideoDirection.FIXED_AT_START))
        assertEquals(90, CameraCapturePolicy.outputRotation(1280, 720, 0, VideoDirection.PORTRAIT))
        assertEquals(180, CameraCapturePolicy.outputRotation(1280, 720, 90, VideoDirection.LANDSCAPE))
        assertEquals(270, CameraCapturePolicy.outputRotation(720, 1280, 270, VideoDirection.LANDSCAPE))
    }

    private fun size(width: Int, height: Int) = CameraCapturePolicy.Resolution(width, height)
    private fun fps(lower: Int, upper: Int) = CameraCapturePolicy.FrameRate(lower, upper)

    @Test
    fun a720pRequestCannotSelectThe1944SquareModeSeenInThePhoneDump() {
        val supported = listOf(size(1944, 1944), size(1920, 1080), size(1280, 960), size(1280, 720), size(640, 480))
        for (requested in listOf(size(1280, 720), size(720, 1280))) {
            assertEquals(listOf(size(1280, 720), size(640, 480)), CameraCapturePolicy.boundedResolutions(supported, requested))
        }
    }

    @Test
    fun aRequestBelowEveryCameraModeFallsBackToTheSmallestSupportedMode() {
        assertEquals(listOf(size(640, 480)), CameraCapturePolicy.boundedResolutions(
            listOf(size(1920, 1080), size(1280, 720), size(640, 480)), size(320, 240)
        ))
    }

    @Test
    fun requestsSupported60FpsInsteadOfLeavingTheCameraAtItsDefault30() {
        assertEquals(fps(60, 60), CameraCapturePolicy.frameRate(listOf(fps(15, 30), fps(30, 60), fps(60, 60)), 60))
    }

    @Test
    fun unsupportedTargetsUseSupportedRanges() {
        val supported = listOf(fps(15, 30), fps(30, 30))
        assertEquals(fps(30, 30), CameraCapturePolicy.frameRate(supported, 60))
        assertEquals(fps(15, 30), CameraCapturePolicy.frameRate(supported, 24))
        assertEquals(fps(30, 30), CameraCapturePolicy.frameRate(emptyList(), 30))
    }
}
