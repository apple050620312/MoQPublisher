package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimestampMapperTest {
    @Test
    fun sensorCadenceIsPreservedInsteadOfCallbackJitter() {
        val mapper = VideoTimestampMapper()
        val first = mapper.map(10_000_000_000L, 1_000_000_000L, 1_000_000_000L)
        val second = mapper.map(10_016_666_667L, 1_030_000_000L, 1_000_000_000L)
        assertEquals(0L, first.timestampUs)
        assertEquals(16_666L, second.timestampUs)
        assertFalse(second.reset)
    }

    @Test
    fun cameraClockResetReanchorsNearCurrentStreamTime() {
        val mapper = VideoTimestampMapper()
        mapper.map(10_000_000_000L, 1_000_000_000L, 1_000_000_000L)
        val reset = mapper.map(100_000_000L, 2_500_000_000L, 1_000_000_000L)
        assertTrue(reset.reset)
        assertEquals(1_500_000L, reset.timestampUs)
    }

    @Test
    fun duplicateSensorTimestampsRemainStrictlyMonotonic() {
        val mapper = VideoTimestampMapper()
        val first = mapper.map(10_000_000_000L, 1_000_000_000L, 1_000_000_000L)
        val second = mapper.map(10_000_000_000L, 1_010_000_000L, 1_000_000_000L)
        assertEquals(first.timestampUs + 1L, second.timestampUs)
    }
}
