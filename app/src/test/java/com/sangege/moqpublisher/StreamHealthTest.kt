package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamHealthTest {
    private val healthy = StreamHealthInput(
        active = true,
        relayPhase = RelayConnectionPhase.CONNECTED,
        videoDemanded = true,
        cameraAgeMs = 100,
        encoderAgeMs = 100,
        publishedAgeMs = 100,
        audioExpected = true,
        audioAgeMs = 100,
        targetFps = 30,
        publishedFps = 30.0,
        queueDepth = 2
    )

    @Test
    fun noSubscriberDoesNotBecomeAStall() {
        val snapshot = StreamHealthEvaluator().evaluate(StreamHealthInput(active = true))
        assertEquals(StreamHealthLevel.HEALTHY, snapshot.level)
        assertTrue(snapshot.reasons.isEmpty())
    }

    @Test
    fun encoderAndPublisherStallsAreCriticalOnlyWhenDemanded() {
        val evaluator = StreamHealthEvaluator()
        assertEquals(StreamHealthLevel.HEALTHY, evaluator.evaluate(healthy.copy(videoDemanded = false, encoderAgeMs = 10_000)).level)
        val critical = evaluator.evaluate(healthy.copy(encoderAgeMs = 3_001, publishedAgeMs = 5_001))
        assertEquals(StreamHealthLevel.CRITICAL, critical.level)
        assertTrue(StreamHealthReason.ENCODER_STALLED in critical.reasons)
        assertTrue(StreamHealthReason.PUBLISH_STALLED in critical.reasons)
    }

    @Test
    fun warningRecoversWithHysteresis() {
        val evaluator = StreamHealthEvaluator(cleanSamplesToRecover = 3)
        assertEquals(StreamHealthLevel.WARNING, evaluator.evaluate(healthy.copy(cameraAgeMs = 3_001)).level)
        assertEquals(StreamHealthLevel.RECOVERING, evaluator.evaluate(healthy).level)
        assertEquals(StreamHealthLevel.RECOVERING, evaluator.evaluate(healthy).level)
        assertEquals(StreamHealthLevel.HEALTHY, evaluator.evaluate(healthy).level)
    }

    @Test
    fun reconnectingIsRecoveringAndAudioGapIsWarning() {
        val evaluator = StreamHealthEvaluator()
        assertEquals(StreamHealthLevel.WARNING, evaluator.evaluate(healthy.copy(relayPhase = RelayConnectionPhase.RECONNECTING)).level)
        val audio = evaluator.evaluate(healthy.copy(audioAgeMs = 2_001, relayPhase = RelayConnectionPhase.CONNECTED))
        assertEquals(StreamHealthLevel.WARNING, audio.level)
        assertTrue(StreamHealthReason.AUDIO_STALLED in audio.reasons)
    }

    @Test
    fun queueAgeAndDepthRaisePressureWithoutMakingItCritical() {
        val evaluator = StreamHealthEvaluator()
        val age = evaluator.evaluate(healthy.copy(queueDepth = 2, queueMediaAgeMs = 401L))
        assertEquals(StreamHealthLevel.WARNING, age.level)
        assertTrue(StreamHealthReason.QUEUE_PRESSURE in age.reasons)
        val depth = evaluator.evaluate(healthy.copy(queueDepth = 6, queueMediaAgeMs = null))
        assertEquals(StreamHealthLevel.WARNING, depth.level)
        assertTrue(StreamHealthReason.QUEUE_PRESSURE in depth.reasons)
    }

    @Test
    fun resetReturnsToIdle() {
        val evaluator = StreamHealthEvaluator()
        evaluator.evaluate(healthy)
        assertEquals(StreamHealthLevel.IDLE, evaluator.reset().level)
    }
}
