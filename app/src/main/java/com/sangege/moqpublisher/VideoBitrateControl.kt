package com.sangege.moqpublisher

/** Leaves transport/audio headroom; drops promptly and recovers gradually. */
internal class VideoBitrateControl(private val ceilingBps: Long, private val audioBps: Long) {
    init {
        require(ceilingBps > 0 && audioBps >= 0)
    }
    var currentBps = ceilingBps
        private set

    private var smoothedCapacityBps: Double? = null

    /**
     * Adjust the encoder toward a stable share of the uplink.  Downward
     * changes use the raw estimate so a real congestion event is handled
     * immediately; upward changes use an asymmetric EMA to avoid bitrate
     * hunting on noisy Wi-Fi/cellular estimates.
     */
    fun update(capacityBps: ULong?, queueDepth: Int = 0, rttMs: Long? = null): Long? {
        if (capacityBps == null) return null

        val rawCapacity = capacityBps.toDouble().coerceAtMost(Long.MAX_VALUE.toDouble())
        val previous = smoothedCapacityBps
        val alpha = when {
            previous == null -> 1.0
            rawCapacity < previous -> 0.55 // react quickly to a real drop
            else -> 0.20 // recover only after several healthy samples
        }
        smoothedCapacityBps = if (previous == null) rawCapacity
        else previous + (rawCapacity - previous) * alpha

        fun budget(capacity: Double): Long = (capacity * 0.8 - audioBps)
            .toLong().coerceIn(minOf(100_000L, ceilingBps), ceilingBps)

        val rawBudget = budget(rawCapacity)
        val smoothedBudget = budget(smoothedCapacityBps ?: rawCapacity)
        // Use the immediate sample for a clear downward move, but the EMA for
        // recovery. This preserves a small deadband around the current rate.
        var budget = if (rawBudget < currentBps * 0.95) rawBudget else smoothedBudget
        if (queueDepth >= 3) budget = minOf(budget, (budget * 0.75).toLong())
        if (rttMs != null && rttMs >= 500L) budget = minOf(budget, (budget * 0.85).toLong())

        val next = when {
            budget < currentBps * 0.9 -> budget
            // Recover all the way to the requested target. Applying the same
            // 10% deadband upward leaves a healthy link stuck at ~91% forever.
            budget > currentBps -> minOf(budget, currentBps + maxOf(10_000L, currentBps / 10))
            else -> currentBps
        }
        if (next == currentBps) return null
        currentBps = next
        return next
    }
}
