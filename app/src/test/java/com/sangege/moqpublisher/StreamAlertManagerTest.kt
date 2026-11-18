package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAlertManagerTest {
    @Test
    fun deduplicatesActiveAlertAndRecordsResolution() {
        val manager = StreamAlertManager(cooldownMs = 10_000)
        val warning = StreamHealthSnapshot(StreamHealthLevel.WARNING, setOf(StreamHealthReason.CAMERA_STALLED))
        assertEquals(1, manager.update(warning, 0).active.size)
        assertEquals(1, manager.update(warning, 1_000).active.size)
        val resolved = manager.update(StreamHealthSnapshot(StreamHealthLevel.HEALTHY), 2_000)
        assertTrue(resolved.active.isEmpty())
        assertEquals(2, resolved.history.size)
        assertTrue(resolved.history.last().active == false)
    }

    @Test
    fun cooldownPreventsImmediateAlertRebroadcast() {
        val manager = StreamAlertManager(cooldownMs = 10_000)
        val warning = StreamHealthSnapshot(StreamHealthLevel.WARNING, setOf(StreamHealthReason.AUDIO_STALLED))
        manager.update(warning, 0)
        manager.update(StreamHealthSnapshot(StreamHealthLevel.HEALTHY), 1_000)
        val state = manager.update(warning, 2_000)
        assertTrue(state.active.isEmpty())
        assertEquals(2, state.history.size)
        assertEquals(1, manager.update(warning, 11_000).active.size)
    }

    @Test
    fun historyIsBounded() {
        val manager = StreamAlertManager(cooldownMs = 0, maxHistory = 3)
        repeat(5) { index ->
            val warning = StreamHealthSnapshot(StreamHealthLevel.WARNING, setOf(StreamHealthReason.QUEUE_PRESSURE))
            manager.update(warning, index * 2L)
            manager.update(StreamHealthSnapshot(StreamHealthLevel.HEALTHY), index * 2L + 1)
        }
        assertEquals(3, manager.update(StreamHealthSnapshot(StreamHealthLevel.HEALTHY), 100).history.size)
    }

    @Test
    fun criticalAlertsComeFirstAndRecoveryEventsRemainInHistoryAfterStop() {
        val manager = StreamAlertManager()
        val active = manager.update(StreamHealthSnapshot(
            StreamHealthLevel.CRITICAL, setOf(StreamHealthReason.LOW_BATTERY, StreamHealthReason.ENCODER_STALLED)
        ), 1_000)
        assertEquals(StreamAlertType.VIDEO_ENCODER_FAILURE, active.active.first().type)
        manager.event(StreamAlertType.RECOVERY_FAILED, 2_000)
        val stopped = manager.clearActive(2_001)
        assertTrue(stopped.active.isEmpty())
        assertTrue(stopped.history.any { it.type == StreamAlertType.RECOVERY_FAILED && it.active })
        assertEquals(stopped.history.map { it.id }.sorted().distinct(), stopped.history.map { it.id })
        assertEquals(stopped.history, manager.reset(2_100).history)
    }
}
