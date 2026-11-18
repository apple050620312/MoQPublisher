package com.sangege.moqpublisher

import dev.moq.VideoCodec
import java.util.ArrayDeque

/** A compressed frame ready to be handed to the MoQ producer. */
internal data class EncodedVideoPacket(
    val generation: Int,
    val trackName: String,
    val width: Int,
    val height: Int,
    val bitrateBps: Long,
    val framerate: Int,
    val codec: VideoCodec,
    val accessUnit: AnnexB.AccessUnit,
    val headers: ByteArray,
    val timestampUs: Long,
    val enqueuedAtNs: Long = System.nanoTime(),
    val modeChange: LiveVideoModeChange? = null
)

/**
 * A small, non-blocking queue for live video.
 *
 * The queue intentionally never waits on the camera callback.  When it is
 * full, discard the stale GOP and wait for a fresh keyframe. Forwarding deltas
 * after dropping their reference frame would corrupt H.264/H.265 playback.
 */
internal class VideoPublishQueue(capacity: Int = 6) : AutoCloseable {
    init {
        require(capacity >= 2)
    }

    enum class OfferResult { ENQUEUED, DROPPED, RESYNC, CLOSED }

    private val monitor = Object()
    private var capacity = capacity
    private val queue = ArrayDeque<EncodedVideoPacket>(capacity)
    private var closed = false
    private var waitingForKeyframe = false

    fun offer(packet: EncodedVideoPacket): OfferResult = synchronized(monitor) {
        if (closed) return@synchronized OfferResult.CLOSED

        if (packet.accessUnit.keyframe) {
            waitingForKeyframe = false
            // A keyframe is a safe restart point.  Keeping old deltas ahead of
            // it would make the relay/browser wait through stale latency.
            if (queue.isNotEmpty()) queue.clear()
            queue.addLast(packet)
            monitor.notifyAll()
            return@synchronized OfferResult.ENQUEUED
        }

        if (waitingForKeyframe) return@synchronized OfferResult.DROPPED
        if (queue.size >= capacity) {
            queue.clear()
            waitingForKeyframe = true
            return@synchronized OfferResult.RESYNC
        }

        queue.addLast(packet)
        monitor.notifyAll()
        OfferResult.ENQUEUED
    }

    /** Blocks only the dedicated publisher thread, never CameraX. */
    fun take(): EncodedVideoPacket? {
        synchronized(monitor) {
            while (queue.isEmpty() && !closed) monitor.wait()
            if (queue.isEmpty()) return null
            return queue.removeFirst()
        }
    }

    fun resetForFramerate(framerate: Int) = synchronized(monitor) {
        capacity = capacityFor(framerate)
        queue.clear()
        waitingForKeyframe = true
    }

    fun waitForKeyframe() = synchronized(monitor) { queue.clear(); waitingForKeyframe = true }

    fun depth(): Int = synchronized(monitor) { queue.size }

    fun mediaAgeMs(nowNs: Long = System.nanoTime()): Long? = synchronized(monitor) {
        queue.firstOrNull()?.enqueuedAtNs?.let { (nowNs - it).coerceAtLeast(0L) / 1_000_000L }
    }

    companion object {
        /** Keeps up to roughly 250 ms of encoded video during short writer stalls. */
        fun capacityFor(framerate: Int): Int {
            require(framerate in 1..120)
            return ((framerate * 250L + 999L) / 1_000L).toInt().coerceIn(6, 16)
        }
    }

    override fun close() {
        synchronized(monitor) {
            closed = true
            queue.clear()
            monitor.notifyAll()
        }
    }
}
