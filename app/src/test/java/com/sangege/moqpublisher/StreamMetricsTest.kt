package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class StreamMetricsTest {
    @Test
    fun stageTimingsSeparateCopyCostFromMoqPublicationAndResetBetweenSamples() {
        var now = 0L
        val metrics = StreamMetrics { now }
        metrics.timing(StreamMetrics.Stage.PIXEL_COPY, 10_000_000L)
        metrics.timing(StreamMetrics.Stage.PIXEL_COPY, 30_000_000L)
        metrics.timing(StreamMetrics.Stage.MOQ_WRITE, 2_000_000L)
        now = 1_000_000_000L
        val sample = metrics.sample()
        assertEquals(20.0, sample["pixel_copy_avg_ms"])
        assertEquals(30.0, sample["pixel_copy_max_ms"])
        assertEquals(2.0, sample["moq_write_avg_ms"])
        now = 2_000_000_000L
        assertNull(metrics.sample()["pixel_copy_avg_ms"])
        metrics.timing(StreamMetrics.Stage.ENCODER_INPUT, 9_000_000L)
        metrics.reset()
        assertNull(metrics.sample()["encoder_input_avg_ms"])
    }

    @Test
    fun measuresActualFrameRatesAcrossDelayedSamplingAndSeparatesDrops() {
        var now = 0L
        val metrics = StreamMetrics { now }
        repeat(60) { metrics.count(StreamMetrics.Counter.CAMERA) }
        repeat(30) { metrics.count(StreamMetrics.Counter.SUBMITTED) }
        repeat(28) { metrics.count(StreamMetrics.Counter.ENCODED) }
        repeat(20) { metrics.count(StreamMetrics.Counter.PACED_DROP) }
        repeat(10) { metrics.count(StreamMetrics.Counter.BUSY_DROP) }
        now = 2_000_000_000L
        val sample = metrics.sample()
        assertEquals("30.00", sample["camera_fps"])
        assertEquals("15.00", sample["submitted_fps"])
        assertEquals("14.00", sample["encoded_fps"])
        assertEquals(20L, sample["paced_drops"])
        assertEquals(10L, sample["encoder_busy_drops"])
    }

    @Test
    fun reportsFrameHandoffLatencyAndMediaBitrateSeparatelyFromTransport() {
        var now = 100_000_000L
        val metrics = StreamMetrics { now }
        metrics.published(timestampUs = 0L, streamStartNs = 0L, bytes = 1_000)
        now = 200_000_000L
        metrics.published(timestampUs = 150_000L, streamStartNs = 0L, bytes = 2_000)
        now = 1_100_000_000L
        val sample = metrics.sample()
        assertEquals(75.0, sample["video_handoff_avg_ms"])
        assertEquals(100.0, sample["video_handoff_max_ms"])
        assertEquals(24.0, sample["video_media_kbps"])
        assertEquals(900L, sample["last_video_age_ms"])
    }

    @Test
    fun stalledTracksKeepTheirLastActivityAgeWhileIntervalCountsReset() {
        var now = 0L
        val metrics = StreamMetrics { now }
        metrics.count(StreamMetrics.Counter.CAMERA)
        metrics.count(StreamMetrics.Counter.ENCODED)
        metrics.count(StreamMetrics.Counter.AUDIO_PACKET)
        now = 1_000_000_000L
        assertEquals(1L, metrics.sample()["audio_packets"])
        now = 3_000_000_000L
        val stalled = metrics.sample()
        assertEquals("0.00", stalled["camera_fps"])
        assertEquals(0L, stalled["audio_packets"])
        assertEquals(3_000L, stalled["last_camera_age_ms"])
        assertEquals(3_000L, stalled["last_encoded_age_ms"])
        assertEquals(3_000L, stalled["last_audio_age_ms"])
        assertNull(stalled["last_video_age_ms"])
        assertNull(stalled["video_handoff_avg_ms"])
    }

    @Test
    fun restartingBroadcastClearsPreviousActivityAndAudioResets() {
        var now = 0L
        val metrics = StreamMetrics { now }
        metrics.count(StreamMetrics.Counter.AUDIO_PACKET)
        metrics.count(StreamMetrics.Counter.AUDIO_RESET)
        now = 5_000_000_000L
        metrics.reset()
        now = 6_000_000_000L
        val sample = metrics.sample()
        assertEquals(0L, sample["audio_resets"])
        assertNull(sample["last_audio_age_ms"])
        assertEquals(1_000L, sample["interval_ms"])
    }
}
