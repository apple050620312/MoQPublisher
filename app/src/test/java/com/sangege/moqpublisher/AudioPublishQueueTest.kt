package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioPublishQueueTest {
    @Test fun overloadRestartsAtLiveAudioWithoutReplayingTheOldBacklog() {
        val queue = AudioPublishQueue(4)
        repeat(4) { queue.offer(EncodedAudioPacket(it * 20_000L, byteArrayOf(it.toByte()))) }
        assertEquals(AudioPublishQueue.OfferResult.DROPPED, queue.offer(EncodedAudioPacket(80_000, byteArrayOf(4))))
        val next = queue.take()!!
        assertEquals(80_000L, next.timestampUs)
        assertArrayEquals(byteArrayOf(4), next.data)
        assertTrue(next.resetEpoch)
        assertEquals(0, queue.depth())
    }

    @Test fun captureResetSurvivesQueueingAndOnlyResetsOnce() {
        val queue = AudioPublishQueue()
        queue.offer(EncodedAudioPacket(1_000_000, byteArrayOf(1), true))
        queue.offer(EncodedAudioPacket(1_020_000, byteArrayOf(2)))
        assertTrue(queue.take()!!.resetEpoch)
        assertFalse(queue.take()!!.resetEpoch)
    }

    @Test fun reconnectDiscardsOldAudioWithoutClosingTheQueue() {
        val queue = AudioPublishQueue()
        queue.offer(EncodedAudioPacket(20_000, byteArrayOf(1)))
        queue.discardForReconnect()
        assertEquals(0, queue.depth())
        assertEquals(AudioPublishQueue.OfferResult.ENQUEUED, queue.offer(EncodedAudioPacket(40_000, byteArrayOf(2))))
        assertEquals(40_000L, queue.take()!!.timestampUs)
    }

    @Test fun closingAnEmptyQueueReleasesTheWaitingPublisher() {
        val queue = AudioPublishQueue()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val waiting = worker.submit<EncodedAudioPacket?> { queue.take() }
            queue.close()
            assertNull(waiting.get(1, TimeUnit.SECONDS))
            assertEquals(AudioPublishQueue.OfferResult.CLOSED, queue.offer(EncodedAudioPacket(0, byteArrayOf(0))))
        } finally { worker.shutdownNow() }
    }
}
