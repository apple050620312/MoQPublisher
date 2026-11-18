package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastRecoveryTest {
    @Test
    fun watchdogRequiresConsecutiveStallSamplesAndTimeForRecovery() {
        val watchdog = StreamStallRecovery()
        val healthy = StreamHealthSnapshot(level = StreamHealthLevel.HEALTHY)
        val encoderStall = StreamHealthSnapshot(
            level = StreamHealthLevel.CRITICAL, reasons = setOf(StreamHealthReason.ENCODER_STALLED)
        )
        assertEquals(null, watchdog.observe(encoderStall, 1_000, cameraSource = true, videoChanging = false))
        assertEquals(null, watchdog.observe(healthy, 2_000, cameraSource = true, videoChanging = false))
        assertEquals(null, watchdog.observe(encoderStall, 3_000, cameraSource = true, videoChanging = false))
        assertEquals(RecoveryComponent.VIDEO_ENCODER, watchdog.observe(encoderStall, 4_000, cameraSource = true, videoChanging = false))
        assertEquals(null, watchdog.observe(encoderStall, 8_000, cameraSource = true, videoChanging = false))
        assertEquals(RecoveryComponent.VIDEO_ENCODER, watchdog.observe(encoderStall, 9_000, cameraSource = true, videoChanging = false))
    }

    @Test
    fun watchdogSkipsNetworkAndVideoTransitionsAndSeparatesAudioPublishing() {
        val watchdog = StreamStallRecovery()
        val cameraStall = StreamHealthSnapshot(reasons = setOf(StreamHealthReason.CAMERA_STALLED))
        val reconnecting = cameraStall.copy(reasons = cameraStall.reasons + StreamHealthReason.RECONNECTING)
        repeat(3) { offset ->
            val time = offset * 1_000L
            assertEquals(null, watchdog.observe(reconnecting, time, cameraSource = true, videoChanging = false))
            assertEquals(null, watchdog.observe(cameraStall, time, cameraSource = false, videoChanging = false))
            assertEquals(null, watchdog.observe(cameraStall, time, cameraSource = true, videoChanging = true))
        }
        val audio = StreamHealthSnapshot(reasons = setOf(StreamHealthReason.AUDIO_PUBLISH_STALLED))
        assertEquals(null, watchdog.observe(audio, 3_000, cameraSource = true, videoChanging = false))
        assertEquals(RecoveryComponent.AUDIO_PUBLISHER, watchdog.observe(audio, 4_000, cameraSource = true, videoChanging = false))
    }

    @Test
    fun recurrentFaultsUseBoundedExponentialBackoffAndResetAfterStability() {
        var now = 0L
        val coordinator = BroadcastRecoveryCoordinator(
            maxAttempts = 3, initialDelayMs = 250L, maximumDelayMs = 700L, stabilityWindowMs = 30_000L,
            nowMs = { now }
        )
        val first = coordinator.begin(RecoveryComponent.VIDEO_ENCODER, RecoveryClassification.TRANSIENT)
        val second = coordinator.begin(RecoveryComponent.VIDEO_ENCODER, RecoveryClassification.TRANSIENT)
        val third = coordinator.begin(RecoveryComponent.VIDEO_ENCODER, RecoveryClassification.RECOVERABLE)
        val exhausted = coordinator.begin(RecoveryComponent.VIDEO_ENCODER, RecoveryClassification.TRANSIENT)

        assertTrue(first.allowed)
        assertEquals(250L, first.delayMs)
        assertEquals(500L, second.delayMs)
        assertEquals(700L, third.delayMs)
        assertFalse(exhausted.allowed)

        assertEquals(1, coordinator.begin(RecoveryComponent.CAMERA, RecoveryClassification.TRANSIENT).attempt)
        now = 30_000L
        assertEquals(1, coordinator.begin(RecoveryComponent.VIDEO_ENCODER, RecoveryClassification.TRANSIENT).attempt)
    }

    @Test
    fun fatalFailuresAreNeverRetried() {
        val coordinator = BroadcastRecoveryCoordinator()
        val attempt = coordinator.begin(RecoveryComponent.AUDIO_CAPTURE, RecoveryClassification.FATAL)
        assertFalse(attempt.allowed)
        assertEquals(1, attempt.attempt)
    }
}
