package com.sangege.moqpublisher

/** Measures transport throughput, rather than the congestion controller's capacity estimate. */
internal class SendRateEstimator {
    private data class Sample(val bytes: ULong, val timeNanos: Long, val epoch: ULong)

    private var previous: Sample? = null

    fun sample(bytesSent: ULong?, timeNanos: Long, epoch: ULong): Long? {
        if (bytesSent == null) {
            previous = null
            return null
        }

        val current = Sample(bytesSent, timeNanos, epoch)
        val before = previous
        previous = current
        if (before == null || epoch != before.epoch || bytesSent < before.bytes) return null

        val elapsedNanos = timeNanos - before.timeNanos
        if (elapsedNanos <= 0) return null

        // Subtract unsigned counters before converting to Double, preserving small
        // increments even when a long-running connection has a very large total.
        val bytes = (bytesSent - before.bytes).toDouble()
        return (bytes * 8_000_000.0 / elapsedNanos.toDouble()).toLong()
    }
}
