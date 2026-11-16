package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class AudioHealthMetricsTest {
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
