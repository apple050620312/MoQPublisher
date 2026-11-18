package com.sangege.moqpublisher

enum class StreamAlertType(val priority: Int, val label: Int) {
    RECOVERY_FAILED(4, R.string.alert_recovery_failed),
    VIDEO_ENCODER_FAILURE(3, R.string.health_encoder_stalled),
    STREAM_STALLED(3, R.string.alert_stream_stalled),
    AUDIO_CAPTURE_FAILURE(3, R.string.health_audio_stalled),
    RELAY_DISCONNECTED(3, R.string.alert_relay_disconnected),
    RECONNECTING(2, R.string.status_reconnecting),
    DEVICE_OVERHEATING(2, R.string.health_thermal),
    NETWORK_DEGRADED(1, R.string.alert_network_degraded),
    QUEUE_PRESSURE(1, R.string.health_queue_pressure),
    LOW_BATTERY(1, R.string.health_low_battery),
    MEMORY_PRESSURE(1, R.string.health_memory_pressure),
    RECOVERY_SUCCEEDED(0, R.string.alert_recovery_succeeded)
}

enum class StreamAlertMode(val sound: Boolean, val vibration: Boolean, val label: Int) {
    SILENT(false, false, R.string.alert_mode_silent),
    SOUND(true, false, R.string.alert_mode_sound),
    VIBRATION(false, true, R.string.alert_mode_vibration),
    SOUND_AND_VIBRATION(true, true, R.string.alert_mode_sound_and_vibration);

    companion object {
        const val PREFERENCE = "stream_alert_mode"
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: SILENT
    }
}

data class StreamAlert(
    val type: StreamAlertType,
    val active: Boolean,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val id: Long
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
    private var nextId = 1L

    fun reset(nowMs: Long): StreamAlertState {
        clearActive(nowMs)
        lastEmitted.clear()
        return state()
    }

    fun clearActive(nowMs: Long): StreamAlertState {
        active.values.forEach { alert -> addHistory(alert.copy(active = false, lastSeenMs = nowMs, id = nextId++)) }
        active.clear()
        return state()
    }

    fun event(type: StreamAlertType, nowMs: Long): StreamAlertState {
        if (shouldEmit(type, nowMs)) {
            addHistory(StreamAlert(type, active = true, nowMs, nowMs, nextId++))
            lastEmitted[type] = nowMs
        }
        return state()
    }

    fun update(snapshot: StreamHealthSnapshot, nowMs: Long): StreamAlertState {
        val desired = snapshot.reasons.map { reason -> reason.alertType(snapshot.level) }.toSet()
        val removed = active.keys - desired
        removed.forEach { type ->
            active.remove(type)?.let { addHistory(it.copy(active = false, lastSeenMs = nowMs, id = nextId++)) }
        }
        desired.filter { it !in active && shouldEmit(it, nowMs) }.forEach { type ->
            val alert = StreamAlert(type, active = true, firstSeenMs = nowMs, lastSeenMs = nowMs, id = nextId++)
            active[type] = alert
            lastEmitted[type] = nowMs
            addHistory(alert)
        }
        return state()
    }

    private fun shouldEmit(type: StreamAlertType, nowMs: Long): Boolean =
        lastEmitted[type]?.let { nowMs < it || nowMs - it >= cooldownMs } ?: true

    private fun addHistory(alert: StreamAlert) {
        history.addLast(alert)
        while (history.size > maxHistory) history.removeFirst()
    }

    private fun state() = StreamAlertState(active.values.sortedByDescending { it.type.priority }, history.toList())

    private fun StreamHealthReason.alertType(level: StreamHealthLevel): StreamAlertType = when (this) {
        StreamHealthReason.RECONNECTING -> StreamAlertType.RECONNECTING
        StreamHealthReason.CAMERA_STALLED, StreamHealthReason.GPU_STALLED, StreamHealthReason.PUBLISH_STALLED,
        StreamHealthReason.AUDIO_PUBLISH_STALLED -> StreamAlertType.STREAM_STALLED
        StreamHealthReason.ENCODER_STALLED -> StreamAlertType.VIDEO_ENCODER_FAILURE
        StreamHealthReason.AUDIO_STALLED -> StreamAlertType.AUDIO_CAPTURE_FAILURE
        StreamHealthReason.QUEUE_PRESSURE -> StreamAlertType.QUEUE_PRESSURE
        StreamHealthReason.THERMAL -> StreamAlertType.DEVICE_OVERHEATING
        StreamHealthReason.LOW_BATTERY -> StreamAlertType.LOW_BATTERY
        StreamHealthReason.MEMORY_PRESSURE -> StreamAlertType.MEMORY_PRESSURE
        StreamHealthReason.LOW_FPS -> if (level == StreamHealthLevel.CRITICAL) StreamAlertType.STREAM_STALLED else StreamAlertType.NETWORK_DEGRADED
    }
}
