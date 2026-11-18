package com.sangege.moqpublisher

enum class RuntimeBitrateSupport { UNKNOWN, ACCEPTED, REJECTED }

/** All changes are serialized by the streamer's video lock. */
internal class VideoBitrateControl(
    settings: VideoBitrateConfiguration,
    var audioBps: Long
) {
    var settings = settings
        private set
    var currentBps = settings.targetBps
        private set
    var runtimeSupport = RuntimeBitrateSupport.UNKNOWN
        private set
    private var smoothedCapacityBps: Double? = null

    fun encoderOpened() {
        runtimeSupport = RuntimeBitrateSupport.UNKNOWN
        smoothedCapacityBps = null
    }

    fun changeSettings(next: VideoBitrateConfiguration, apply: (Long) -> Unit) {
        val rate = when {
            !next.adaptive -> next.targetBps
            next.targetBps != settings.targetBps -> minOf(currentBps, next.targetBps)
            else -> currentBps
        }.coerceIn(next.minimumBps, next.maximumBps)

        // Store the configuration only after a live encoder accepts the change.
        applyRate(rate, apply)
        settings = next
    }

    fun update(
        capacityBps: ULong?,
        queueDepth: Int = 0,
        queueAgeMs: Long? = null,
        rttMs: Long? = null,
        apply: (Long) -> Unit
    ): Long? {
        if (!settings.adaptive || runtimeSupport == RuntimeBitrateSupport.REJECTED) return null

        val rawCapacity = capacityBps?.toDouble()
        if (rawCapacity != null) {
            val previous = smoothedCapacityBps ?: rawCapacity
            val weight = if (rawCapacity < previous) 0.55 else 0.20
            smoothedCapacityBps = previous + (rawCapacity - previous) * weight
        }

        val headroom = if ((rttMs ?: 0L) >= 500L) 0.68 else 0.8
        fun budget(capacity: Double): Long = (capacity * headroom - audioBps)
            .toLong().coerceIn(settings.minimumBps, settings.maximumBps)

        val queuePressure = queueDepth >= 3 || (queueAgeMs ?: 0) >= 250
        if (queuePressure) {
            val pressureBudget = currentBps * 75L / 100L
            val networkBudget = rawCapacity?.let(::budget) ?: currentBps
            return applyRate(minOf(pressureBudget, networkBudget).coerceAtLeast(settings.minimumBps), apply)
        }

        if (rawCapacity == null) return null
        val rawBudget = budget(rawCapacity)
        val available = if (rawBudget < currentBps * 0.95) rawBudget else budget(checkNotNull(smoothedCapacityBps))
        val next = when {
            available < currentBps * 0.9 -> available
            available > currentBps -> minOf(available, currentBps + maxOf(10_000L, currentBps / 10))
            else -> currentBps
        }
        return applyRate(next, apply)
    }

    private fun applyRate(rate: Long, apply: (Long) -> Unit): Long? {
        if (rate == currentBps) return null
        try {
            apply(rate)
        } catch (error: RuntimeException) {
            runtimeSupport = RuntimeBitrateSupport.REJECTED
            throw error
        }
        currentBps = rate
        runtimeSupport = RuntimeBitrateSupport.ACCEPTED
        return rate
    }
}
