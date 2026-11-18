package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class AudioHealthMetricsTest {
    @Test
    fun exposesPeakRmsAndClippingLevel() {
        val metrics = AudioHealthMetrics { 0L }
        val pcm = byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 0, 0x80.toByte())
        metrics.captured(pcm)
        val level = metrics.level()
        assertTrue(level.peakPercent > 99f)
        assertTrue(level.rmsPercent > 50f)
        assertTrue(level.clipping)
        assertTrue(level.peakDbfs > -1f)
        assertTrue(level.rmsDbfs < 0f)
    }

    @Test
    fun reportsStereoPeaksAndResetsMeterWindowAfterSampling() {
        val metrics = AudioHealthMetrics { 0L }
        metrics.captured(byteArrayOf(0, 0x40, 0, 0x20, 0, 0x40, 0, 0x20), channels = 2)
        val level = metrics.level()
        assertEquals(2, level.channelPeakPercent.size)
        assertTrue(level.channelPeakPercent[0] > level.channelPeakPercent[1])
        assertFalse(level.silent)
        metrics.sample()
        assertFalse(metrics.level().silent)
        assertTrue(metrics.level().channelPeakPercent.isEmpty())
    }
    @Test fun revealsPublisherBurstsEvenWhenAveragePacketRateIsCorrect() {
        var now = 0L
        val metrics = AudioHealthMetrics { now }
        repeat(4) { i ->
            now = i * 20_000_000L
            metrics.captured(byteArrayOf(1, 0))
        }
        for (time in listOf(0L, 40_000_000L, 40_001_000L, 60_000_000L)) {
            now = time
            metrics.published(80, false)
        }
        val sample = metrics.sample()
        assertEquals(20.0, sample["audio_capture_interval_avg_ms"])
        assertEquals(20.0, sample["audio_publish_interval_avg_ms"])
        assertEquals(40.0, sample["audio_publish_interval_max_ms"])
        assertEquals(0.001, sample["audio_publish_interval_min_ms"])
    }

    @Test fun samplingRetainsTheGapAcrossReportingBoundaries() {
        var now = 0L
        val metrics = AudioHealthMetrics { now }
        metrics.published(40, false)
        metrics.sample()
        now = 100_000_000
        metrics.published(40, true)
        val sample = metrics.sample()
        assertEquals(100.0, sample["audio_publish_interval_max_ms"])
        assertEquals(1, sample["audio_groups"])
        assertEquals(40L, sample["audio_encoded_bytes"])
        metrics.reset()
        assertNull(metrics.sample()["audio_publish_interval_max_ms"])
    }

    @Test fun recordsSilenceClippingQueuePeakAndHardwareTimestamp() {
        val metrics = AudioHealthMetrics { 2_000_000_000L }
        metrics.captured(ByteArray(8))
        metrics.captured(byteArrayOf(0, -128)) // Short.MIN_VALUE, without abs(Short) overflow.
        metrics.queueDepth(3)
        metrics.queueDepth(0)
        metrics.hardwareClock(48_000, 48_960, 1_990_000_000)
        val sample = metrics.sample()
        assertEquals(2, sample["audio_capture_packets"])
        assertEquals(1, sample["audio_pcm_silent_packets"])
        assertEquals(100.0, sample["audio_pcm_peak_percent"])
        assertEquals(3, sample["audio_queue_max_depth"])
        assertEquals(20.0, sample["audio_record_unread_ms"])
        assertEquals(10.0, sample["audio_record_timestamp_age_ms"])
        assertEquals(0, metrics.sample()["audio_pcm_silent_packets"])
    }
}
