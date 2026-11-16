package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class AudioTimelineTest {
    @Test
    fun timestampsTheFirstSampleInsteadOfTheEndOfTheRead() {
        val stamp = AudioTimeline().next(1_020_000L)
        assertEquals(1_000_000L, stamp.timestampUs)
        assertFalse(stamp.reset)
    }

    @Test
    fun schedulingJitterDoesNotResetOpus() {
        val timeline = AudioTimeline()
        for (packet in 0L until 300L) {
            val jitter = if (packet % 2L == 0L) 0L else 10_000L
            val stamp = timeline.next(1_020_000L + packet * 20_000L + jitter)
            assertFalse(stamp.reset)
            assertEquals(1_000_000L + packet * 20_000L, stamp.timestampUs)
        }
    }

    @Test
    fun aCaptureGapResetsTheNativeSampleClockAndStaysOnLiveTime() {
        val timeline = AudioTimeline()
        timeline.next(1_020_000L)
        timeline.next(1_040_000L)
        val resumed = timeline.next(3_020_000L)
        assertTrue(resumed.reset)
        assertEquals(3_000_000L, resumed.timestampUs)
        val next = timeline.next(3_040_000L)
        assertFalse(next.reset)
        assertEquals(3_020_000L, next.timestampUs)
    }
}
