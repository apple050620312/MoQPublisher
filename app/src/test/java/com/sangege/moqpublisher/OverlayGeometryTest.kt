package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    @Test
    fun previewFitsTheFullEncodedFrame() {
        assertEquals(FrameViewport(0, 697, 1080, 606), FrameViewport.fit(1080, 2000, 1280, 718))
        assertEquals(FrameViewport(393, 0, 293, 520), FrameViewport.fit(1080, 520, 720, 1280))
    }

    @Test
    fun rightAnchoringStaysInsideTheFrameEvenForAnOversizedImage() {
        val frame = FrameViewport(45, 15, 360, 640)
        val settings = OverlaySettings(position = OverlayPosition.TOP_RIGHT, scale = 200)
        val placement = settings.placement(frame, 1000, 800)
        assertTrue(placement.x >= frame.x && placement.y >= frame.y)
        assertEquals(frame.x + frame.width - 10, placement.x + placement.width)
        assertEquals(frame.y + frame.height - 10, placement.y + placement.height)
    }

    @Test
    fun normalizationKeepsUnicodeAndClampsAppearanceSettings() {
        val settings = OverlaySettings(text = "直播\n計時", watermark = "🔴".repeat(120), scale = 300, opacity = -1).normalized()
        assertEquals("直播 計時", settings.text)
        assertEquals("🔴".repeat(96), settings.watermark)
        assertEquals(200, settings.scale)
        assertEquals(10, settings.opacity)
    }
}
