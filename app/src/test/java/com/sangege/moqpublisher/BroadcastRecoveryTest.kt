package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastRecoveryTest {
    @Test
    fun retriesUseBoundedExponentialBackoffAndResetAfterSuccess() {
        val coordinator = BroadcastRecoveryCoordinator(maxAttempts = 3, initialDelayMs = 250L, maximumDelayMs = 700L)
        val first = coordinator.begin(RecoveryComponent.VIDEO_ENCODER, RecoveryClassification.TRANSIENT)
        val second = coordinator.begin(RecoveryComponent.VIDEO_ENCODER, RecoveryClassification.TRANSIENT)
        val third = coordinator.begin(RecoveryComponent.VIDEO_ENCODER, RecoveryClassification.RECOVERABLE)
        val exhausted = coordinator.begin(RecoveryComponent.VIDEO_ENCODER, RecoveryClassification.TRANSIENT)

        assertTrue(first.allowed)
        assertEquals(250L, first.delayMs)
        assertEquals(500L, second.delayMs)
        assertEquals(700L, third.delayMs)
        assertFalse(exhausted.allowed)

        coordinator.succeeded(RecoveryComponent.VIDEO_ENCODER)
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
