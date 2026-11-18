package com.sangege.moqpublisher

enum class RecoveryComponent {
    CAMERA,
    VIDEO_ENCODER,
    VIDEO_PUBLISH_QUEUE,
    AUDIO_CAPTURE
}

enum class RecoveryClassification {
    RECOVERABLE,
    TRANSIENT,
    FATAL
}

data class RecoveryAttempt(
    val component: RecoveryComponent,
    val attempt: Int,
    val delayMs: Long,
    val allowed: Boolean,
    val classification: RecoveryClassification
)

/**
 * Keeps media recovery finite and independent for each component. Repeated
 * faults within the stability window consume the same budget, even if a
 * CameraX rebind or codec restart returned before the fault recurred.
 */
internal class BroadcastRecoveryCoordinator(
    private val maxAttempts: Int = 3,
    private val initialDelayMs: Long = 250L,
    private val maximumDelayMs: Long = 4_000L,
    private val stabilityWindowMs: Long = 30_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private data class Failure(val attempts: Int, val atMs: Long)
    private val failures = mutableMapOf<RecoveryComponent, Failure>()

    init {
        require(maxAttempts > 0)
        require(initialDelayMs >= 0L)
        require(maximumDelayMs >= initialDelayMs)
        require(stabilityWindowMs >= maximumDelayMs)
    }

    @Synchronized
    fun begin(component: RecoveryComponent, classification: RecoveryClassification): RecoveryAttempt {
        val now = nowMs()
        val previous = failures[component]?.takeIf { now - it.atMs < stabilityWindowMs }
        val next = (previous?.attempts ?: 0) + 1
        if (classification == RecoveryClassification.FATAL || next > maxAttempts) {
            return RecoveryAttempt(component, next, 0L, allowed = false, classification)
        }
        failures[component] = Failure(next, now)
        val multiplier = 1L shl (next - 1).coerceAtMost(30)
        val delay = (initialDelayMs * multiplier).coerceAtMost(maximumDelayMs)
        return RecoveryAttempt(component, next, delay, allowed = true, classification)
    }

    @Synchronized
    fun reset() {
        failures.clear()
    }
}
