package com.sangege.moqpublisher

import dev.moq.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPublishQueueTest {
    @Test
    fun capacityScalesWithFrameRateWithoutUnboundedLatency() {
        assertEquals(6, VideoPublishQueue.capacityFor(15))
        assertEquals(8, VideoPublishQueue.capacityFor(30))
        assertEquals(15, VideoPublishQueue.capacityFor(60))
        assertEquals(16, VideoPublishQueue.capacityFor(120))
    }

    @Test fun reconnectDropsOldGopAndRequiresANewKeyframe() {
        val queue = VideoPublishQueue(3)
        queue.offer(packet(1, true))
        queue.offer(packet(2))
        queue.waitForKeyframe()
        assertEquals(0, queue.depth())
        assertEquals(VideoPublishQueue.OfferResult.DROPPED, queue.offer(packet(3)))
        assertEquals(VideoPublishQueue.OfferResult.ENQUEUED, queue.offer(packet(4, true)))
        assertEquals(4L, queue.take()?.timestampUs)
    }
    private fun packet(timestamp: Long, keyframe: Boolean = false) = EncodedVideoPacket(
        generation = 1,
        trackName = "camera",
        width = 1280,
        height = 720,
        bitrateBps = 2_000_000L,
        framerate = 30,
        codec = VideoCodec.H264,
        accessUnit = AnnexB.AccessUnit(byteArrayOf(timestamp.toByte()), keyframe),
        headers = byteArrayOf(1, 2, 3),
        timestampUs = timestamp
    )

    @Test
    fun overflowWaitsForAKeyframeInsteadOfForwardingBrokenDeltaDependencies() {
        val queue = VideoPublishQueue(2)
        assertEquals(VideoPublishQueue.OfferResult.ENQUEUED, queue.offer(packet(1)))
        assertEquals(VideoPublishQueue.OfferResult.ENQUEUED, queue.offer(packet(2)))
        assertEquals(VideoPublishQueue.OfferResult.RESYNC, queue.offer(packet(3)))
        assertEquals(0, queue.depth())
        assertEquals(VideoPublishQueue.OfferResult.DROPPED, queue.offer(packet(4)))
        assertEquals(VideoPublishQueue.OfferResult.ENQUEUED, queue.offer(packet(5, keyframe = true)))
        assertEquals(VideoPublishQueue.OfferResult.ENQUEUED, queue.offer(packet(6)))
        assertEquals(5L, queue.take()?.timestampUs)
        assertEquals(6L, queue.take()?.timestampUs)
    }

    @Test
    fun keyframeReplacesStaleBacklog() {
        val queue = VideoPublishQueue(3)
        queue.offer(packet(1, keyframe = true))
        queue.offer(packet(2))
        queue.offer(packet(3))
        queue.offer(packet(4, keyframe = true))
        val latest = queue.take()
        assertNotNull(latest)
        assertTrue(latest!!.accessUnit.keyframe)
        assertEquals(4L, latest.timestampUs)
        assertEquals(0, queue.depth())
    }

    @Test
    fun reportsAgeOfTheOldestQueuedFrame() {
        val queue = VideoPublishQueue(3)
        queue.offer(packet(1, keyframe = true).copy(enqueuedAtNs = 1_000_000L))
        queue.offer(packet(2).copy(enqueuedAtNs = 2_000_000L))
        assertEquals(499L, queue.mediaAgeMs(500_000_000L))
        queue.take()
        assertEquals(498L, queue.mediaAgeMs(500_000_000L))
    }
}
