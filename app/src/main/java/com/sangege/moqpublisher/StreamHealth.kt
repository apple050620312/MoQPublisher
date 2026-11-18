package com.sangege.moqpublisher

/** Health levels are independent from relay connection and remote decode status. */
enum class StreamHealthLevel { IDLE, HEALTHY, WARNING, CRITICAL, RECOVERING }

enum class StreamHealthReason {
    RECONNECTING,
    CAMERA_STALLED,
    GPU_STALLED,
    ENCODER_STALLED,
    PUBLISH_STALLED,
    AUDIO_STALLED,
    AUDIO_PUBLISH_STALLED,
    LOW_FPS,
    QUEUE_PRESSURE,
    LOW_BATTERY,
    THERMAL,
    MEMORY_PRESSURE
}

data class StreamHealthSnapshot(
    val level: StreamHealthLevel = StreamHealthLevel.IDLE,
    val reasons: Set<StreamHealthReason> = emptySet(),
    val cleanSamples: Int = 0
) {
    val hasAlert: Boolean get() = level == StreamHealthLevel.WARNING || level == StreamHealthLevel.CRITICAL
}

data class StreamHealthInput(
    val active: Boolean,
    val relayPhase: RelayConnectionPhase? = null,
    val videoExpected: Boolean = false,
    val videoDemanded: Boolean = false,
    val cameraAgeMs: Long? = null,
    val gpuAgeMs: Long? = null,
    val encoderAgeMs: Long? = null,
    val publishedAgeMs: Long? = null,
    val audioExpected: Boolean = false,
    val audioCaptureAgeMs: Long? = null,
    val audioPublishAgeMs: Long? = null,
    val elapsedMs: Long = 0L,
    val intervalMs: Long = 0L,
    val targetFps: Int = 0,
    val publishedFps: Double? = null,
    val queueDepth: Int = 0,
    val queueMediaAgeMs: Long? = null,
    val deviceHealthIssues: Set<DeviceHealthIssue> = emptySet()
)

internal data class StreamHealthThresholds(
    val cameraGapMs: Long = 3_000L,
    val gpuGapMs: Long = 3_000L,
    val encoderGapMs: Long = 3_000L,
    val publishGapMs: Long = 5_000L,
    val audioGapMs: Long = 2_000L,
    val lowFpsWindowMs: Long = 10_000L,
    val lowFpsRatio: Double = 0.70,
    val queueDepth: Int = 6,
    val queueAgeMs: Long = 400L
)

/** Pure evaluator used by the streamer and JVM tests. */
internal class StreamHealthEvaluator(
    private val cleanSamplesToRecover: Int = 3,
    private val thresholds: StreamHealthThresholds = StreamHealthThresholds()
) {
    private var previous = StreamHealthSnapshot()
    private var lowFpsMs = 0L

    @Synchronized
    fun reset(): StreamHealthSnapshot {
        previous = StreamHealthSnapshot()
        lowFpsMs = 0L
        return previous
    }

    @Synchronized
    fun markRecovering(): StreamHealthSnapshot {
        previous = previous.copy(level = StreamHealthLevel.RECOVERING, cleanSamples = 0)
        return previous
    }

    @Synchronized
    fun evaluate(input: StreamHealthInput): StreamHealthSnapshot {
        if (!input.active) return reset()

        val reasons = linkedSetOf<StreamHealthReason>()
        val reconnecting = input.relayPhase == RelayConnectionPhase.RECONNECTING ||
            input.relayPhase == RelayConnectionPhase.RECOVERING
        if (reconnecting) reasons += StreamHealthReason.RECONNECTING

        fun expired(ageMs: Long?, thresholdMs: Long) = (ageMs ?: input.elapsedMs) > thresholdMs
        if (input.videoExpected || input.videoDemanded) {
            val cameraStalled = expired(input.cameraAgeMs, thresholds.cameraGapMs)
            val encoderStalled = expired(input.encoderAgeMs, thresholds.encoderGapMs)
            if (cameraStalled) reasons += StreamHealthReason.CAMERA_STALLED
            if (!cameraStalled && encoderStalled) {
                reasons += if (expired(input.gpuAgeMs, thresholds.gpuGapMs)) StreamHealthReason.GPU_STALLED
                    else StreamHealthReason.ENCODER_STALLED
            }
        }
        if (!reconnecting && input.videoDemanded && expired(input.publishedAgeMs, thresholds.publishGapMs)) {
            reasons += StreamHealthReason.PUBLISH_STALLED
        }
        val lowFps = !reconnecting && input.videoDemanded && input.targetFps > 0 &&
            input.publishedFps != null && input.publishedFps < input.targetFps * thresholds.lowFpsRatio
        lowFpsMs = if (lowFps) (lowFpsMs + input.intervalMs).coerceAtMost(thresholds.lowFpsWindowMs) else 0L
        if (lowFpsMs >= thresholds.lowFpsWindowMs) reasons += StreamHealthReason.LOW_FPS

        if (input.audioExpected) {
            if (expired(input.audioCaptureAgeMs, thresholds.audioGapMs)) {
                reasons += StreamHealthReason.AUDIO_STALLED
            } else if (!reconnecting && expired(input.audioPublishAgeMs, thresholds.publishGapMs)) {
                reasons += StreamHealthReason.AUDIO_PUBLISH_STALLED
            }
        }
        if (input.queueDepth >= thresholds.queueDepth || (input.queueMediaAgeMs ?: 0L) > thresholds.queueAgeMs) {
            reasons += StreamHealthReason.QUEUE_PRESSURE
        }
        if (DeviceHealthIssue.LOW_BATTERY in input.deviceHealthIssues) reasons += StreamHealthReason.LOW_BATTERY
        if (DeviceHealthIssue.THERMAL in input.deviceHealthIssues) reasons += StreamHealthReason.THERMAL
        if (DeviceHealthIssue.MEMORY_PRESSURE in input.deviceHealthIssues) reasons += StreamHealthReason.MEMORY_PRESSURE

        val critical = reasons.any {
            it == StreamHealthReason.GPU_STALLED || it == StreamHealthReason.ENCODER_STALLED ||
                it == StreamHealthReason.PUBLISH_STALLED
        }
        val level = when {
            critical -> StreamHealthLevel.CRITICAL
            reasons.isNotEmpty() -> StreamHealthLevel.WARNING
            previous.level == StreamHealthLevel.CRITICAL || previous.level == StreamHealthLevel.WARNING ||
                previous.level == StreamHealthLevel.RECOVERING -> {
                val clean = previous.cleanSamples + 1
                if (clean >= cleanSamplesToRecover) StreamHealthLevel.HEALTHY else StreamHealthLevel.RECOVERING
            }
            else -> StreamHealthLevel.HEALTHY
        }
        val cleanSamples = if (reasons.isEmpty()) (previous.cleanSamples + 1).coerceAtMost(cleanSamplesToRecover) else 0
        return StreamHealthSnapshot(level, reasons, cleanSamples).also { previous = it }
    }
}
