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
 * Keeps media recovery finite and independent for each component. A successful
 * repair clears only that component's counter, so a camera repair does not
 * grant unlimited retries to a failing audio device.
 */
internal class BroadcastRecoveryCoordinator(
    private val maxAttempts: Int = 3,
    private val initialDelayMs: Long = 250L,
    private val maximumDelayMs: Long = 4_000L
) {
    private val attempts = mutableMapOf<RecoveryComponent, Int>()

    init {
        require(maxAttempts > 0)
        require(initialDelayMs >= 0L)
        require(maximumDelayMs >= initialDelayMs)
    }

    @Synchronized
    fun begin(component: RecoveryComponent, classification: RecoveryClassification): RecoveryAttempt {
        val next = (attempts[component] ?: 0) + 1
        if (classification == RecoveryClassification.FATAL || next > maxAttempts) {
            return RecoveryAttempt(component, next, 0L, allowed = false, classification)
        }
        attempts[component] = next
        val multiplier = 1L shl (next - 1).coerceAtMost(30)
        val delay = (initialDelayMs * multiplier).coerceAtMost(maximumDelayMs)
        return RecoveryAttempt(component, next, delay, allowed = true, classification)
    }

    @Synchronized
    fun succeeded(component: RecoveryComponent) {
        attempts.remove(component)
    }

    @Synchronized
    fun reset() {
        attempts.clear()
    }
}
