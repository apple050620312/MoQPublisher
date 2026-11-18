package com.sangege.moqpublisher

enum class StreamAlertType {
    NETWORK_DEGRADED,
    STREAM_STALLED,
    VIDEO_ENCODER_FAILURE,
    AUDIO_CAPTURE_FAILURE,
    QUEUE_PRESSURE,
    DEVICE_OVERHEATING,
    LOW_BATTERY,
    MEMORY_PRESSURE
}

data class StreamAlert(
    val type: StreamAlertType,
    val active: Boolean,
    val firstSeenMs: Long,
    val lastSeenMs: Long
)

data class StreamAlertState(
    val active: List<StreamAlert> = emptyList(),
    val history: List<StreamAlert> = emptyList()
)

/** Deduplicates health alerts and keeps a bounded history for diagnostics/UI. */
internal class StreamAlertManager(
    private val cooldownMs: Long = 10_000L,
    private val maxHistory: Int = 20
) {
    private val active = linkedMapOf<StreamAlertType, StreamAlert>()
    private val lastEmitted = mutableMapOf<StreamAlertType, Long>()
    private val history = ArrayDeque<StreamAlert>()

    fun reset(): StreamAlertState {
        active.clear()
        lastEmitted.clear()
        history.clear()
        return state()
    }

    fun update(snapshot: StreamHealthSnapshot, nowMs: Long): StreamAlertState {
        val desired = snapshot.reasons.mapNotNull { reason -> reason.alertType(snapshot.level) }.toSet()
        val removed = active.keys - desired
        removed.forEach { type ->
            active.remove(type)?.let { addHistory(it.copy(active = false, lastSeenMs = nowMs)) }
        }
        desired.forEach { type ->
            val current = active[type]
            if (current != null) {
                active[type] = current.copy(lastSeenMs = nowMs)
            } else if (lastEmitted[type] == null || nowMs - lastEmitted.getValue(type) >= cooldownMs) {
                val alert = StreamAlert(type, active = true, firstSeenMs = nowMs, lastSeenMs = nowMs)
                active[type] = alert
                lastEmitted[type] = nowMs
                addHistory(alert)
            }
        }
        return state()
    }

    private fun addHistory(alert: StreamAlert) {
        history.addLast(alert)
        while (history.size > maxHistory) history.removeFirst()
    }

    private fun state() = StreamAlertState(active.values.toList(), history.toList())

    private fun StreamHealthReason.alertType(level: StreamHealthLevel): StreamAlertType? = when (this) {
        StreamHealthReason.RECONNECTING -> StreamAlertType.NETWORK_DEGRADED
        StreamHealthReason.CAMERA_STALLED, StreamHealthReason.PUBLISH_STALLED -> StreamAlertType.STREAM_STALLED
        StreamHealthReason.ENCODER_STALLED -> StreamAlertType.VIDEO_ENCODER_FAILURE
        StreamHealthReason.AUDIO_STALLED -> StreamAlertType.AUDIO_CAPTURE_FAILURE
        StreamHealthReason.QUEUE_PRESSURE -> StreamAlertType.QUEUE_PRESSURE
        StreamHealthReason.THERMAL -> StreamAlertType.DEVICE_OVERHEATING
        StreamHealthReason.LOW_BATTERY -> StreamAlertType.LOW_BATTERY
        StreamHealthReason.MEMORY_PRESSURE -> StreamAlertType.MEMORY_PRESSURE
        StreamHealthReason.LOW_FPS -> if (level == StreamHealthLevel.CRITICAL) StreamAlertType.STREAM_STALLED else StreamAlertType.NETWORK_DEGRADED
    }
}
