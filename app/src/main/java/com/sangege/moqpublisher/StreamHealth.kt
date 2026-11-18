package com.sangege.moqpublisher

/** Health levels are independent from relay connection and remote decode status. */
enum class StreamHealthLevel { IDLE, HEALTHY, WARNING, CRITICAL, RECOVERING }

enum class StreamHealthReason {
    RECONNECTING,
    CAMERA_STALLED,
    ENCODER_STALLED,
    PUBLISH_STALLED,
    AUDIO_STALLED,
    LOW_FPS,
    QUEUE_PRESSURE
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
    val videoDemanded: Boolean = false,
    val cameraAgeMs: Long? = null,
    val encoderAgeMs: Long? = null,
    val publishedAgeMs: Long? = null,
    val audioExpected: Boolean = false,
    val audioAgeMs: Long? = null,
    val targetFps: Int = 0,
    val publishedFps: Double? = null,
    val queueDepth: Int = 0
)

/** Pure evaluator used by the streamer and JVM tests. */
internal class StreamHealthEvaluator(private val cleanSamplesToRecover: Int = 3) {
    private var previous = StreamHealthSnapshot()

    fun reset(): StreamHealthSnapshot {
        previous = StreamHealthSnapshot()
        return previous
    }

    fun evaluate(input: StreamHealthInput): StreamHealthSnapshot {
        if (!input.active) return reset()

        val reasons = linkedSetOf<StreamHealthReason>()
        if (input.relayPhase == RelayConnectionPhase.RECONNECTING) reasons += StreamHealthReason.RECONNECTING
        if (input.videoDemanded) {
            if (input.cameraAgeMs != null && input.cameraAgeMs > 3_000L) reasons += StreamHealthReason.CAMERA_STALLED
            if (input.encoderAgeMs != null && input.encoderAgeMs > 3_000L) reasons += StreamHealthReason.ENCODER_STALLED
            if (input.publishedAgeMs != null && input.publishedAgeMs > 5_000L) reasons += StreamHealthReason.PUBLISH_STALLED
            if (input.targetFps > 0 && input.publishedFps != null && input.publishedFps < input.targetFps * 0.7) {
                reasons += StreamHealthReason.LOW_FPS
            }
        }
        if (input.audioExpected && input.audioAgeMs != null && input.audioAgeMs > 2_000L) {
            reasons += StreamHealthReason.AUDIO_STALLED
        }
        if (input.queueDepth >= 45) reasons += StreamHealthReason.QUEUE_PRESSURE

        val critical = reasons.any {
            it == StreamHealthReason.ENCODER_STALLED || it == StreamHealthReason.PUBLISH_STALLED
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
        val cleanSamples = if (reasons.isEmpty()) previous.cleanSamples + 1 else 0
        return StreamHealthSnapshot(level, reasons, cleanSamples).also { previous = it }
    }
}
