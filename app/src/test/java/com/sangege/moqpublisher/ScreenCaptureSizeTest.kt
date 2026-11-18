package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCaptureSizeTest {
    @Test
    fun rotatedContentRetainsAspectRatioAndUsesEvenEncoderDimensions() {
        assertEquals(ScreenCaptureSize(1280, 576), ScreenCaptureSize.fit(2400, 1080, 1280))
        assertEquals(ScreenCaptureSize(576, 1280), ScreenCaptureSize.fit(1080, 2400, 1280))
    }

    @Test
    fun contentWithinTheLimitKeepsItsSizeAndOddBoundsDoNotExceedTheLimit() {
        assertEquals(ScreenCaptureSize(640, 360), ScreenCaptureSize.fit(640, 360, 1280))
        assertEquals(ScreenCaptureSize(1280, 720), ScreenCaptureSize.fit(1920, 1080, 1281))
    }
}
