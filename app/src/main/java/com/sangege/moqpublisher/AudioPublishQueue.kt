package com.sangege.moqpublisher

import java.util.ArrayDeque

internal data class EncodedAudioPacket(
    val timestampUs: Long,
    val data: ByteArray,
    val resetEpoch: Boolean = false,
    val enqueuedAtNs: Long = System.nanoTime()
)

/** Keeps microphone publication live when the native/network writer pauses. */
internal class AudioPublishQueue(private val capacity: Int = 4) : AutoCloseable {
    init {
        require(capacity >= 2)
    }

    enum class OfferResult { ENQUEUED, DROPPED, CLOSED }

    private val monitor = Object()
    private val queue = ArrayDeque<EncodedAudioPacket>(capacity)
    private var closed = false

    fun offer(packet: EncodedAudioPacket): OfferResult = synchronized(monitor) {
        if (closed) return@synchronized OfferResult.CLOSED
        var dropped = false
        if (queue.size >= capacity) {
            // Audio is also real-time data. Discard the stale backlog and
            // re-anchor the Opus epoch at the newest packet.
            queue.clear()
            dropped = true
        }
        queue.addLast(if (dropped) packet.copy(resetEpoch = true) else packet)
        monitor.notifyAll()
        if (dropped) OfferResult.DROPPED else OfferResult.ENQUEUED
    }

    fun take(): EncodedAudioPacket? {
        synchronized(monitor) {
            while (queue.isEmpty() && !closed) monitor.wait()
            if (queue.isEmpty()) return null
            return queue.removeFirst()
        }
    }

    fun depth(): Int = synchronized(monitor) { queue.size }

    fun discardForReconnect() = synchronized(monitor) {
        queue.clear()
    }

    override fun close() {
        synchronized(monitor) {
            closed = true
            queue.clear()
            monitor.notifyAll()
        }
    }
}
