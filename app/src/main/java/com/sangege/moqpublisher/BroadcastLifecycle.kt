package com.sangege.moqpublisher

enum class BroadcastPhase {
    IDLE,
    PREPARING,
    CONNECTING,
    ANNOUNCED,
    WAITING_FOR_SUBSCRIBER,
    PUBLISHING,
    RECORDING,
    DEGRADED,
    RECONNECTING,
    RECOVERING,
    FAILED,
    STOPPING
}

data class BroadcastLifecycleState(
    val phase: BroadcastPhase = BroadcastPhase.IDLE,
    val generation: Long = 0L,
    val error: UiMessage? = null
)

sealed interface BroadcastLifecycleEvent {
    data object StartRequested : BroadcastLifecycleEvent
    data object Connecting : BroadcastLifecycleEvent
    data object Announced : BroadcastLifecycleEvent
    data object RecordingLocally : BroadcastLifecycleEvent
    data class SubscriberDemand(val requested: Boolean) : BroadcastLifecycleEvent
    data class HealthChanged(val degraded: Boolean) : BroadcastLifecycleEvent
    data object RecoveryStarted : BroadcastLifecycleEvent
    data class RecoveryCompleted(val subscriberDemanded: Boolean) : BroadcastLifecycleEvent
    data object Reconnecting : BroadcastLifecycleEvent
    data object Recovered : BroadcastLifecycleEvent
    data object StopRequested : BroadcastLifecycleEvent
    data class Failed(val message: UiMessage) : BroadcastLifecycleEvent
    data object CleanedUp : BroadcastLifecycleEvent
}

/** Pure lifecycle reducer. Illegal transitions keep the previous phase. */
internal object BroadcastLifecyclePolicy {
    fun reduce(state: BroadcastLifecycleState, event: BroadcastLifecycleEvent): BroadcastLifecycleState = when (event) {
        BroadcastLifecycleEvent.StartRequested -> if (state.phase == BroadcastPhase.IDLE) {
            state.copy(phase = BroadcastPhase.PREPARING, generation = state.generation + 1, error = null)
        } else state
        BroadcastLifecycleEvent.Connecting -> if (state.phase in setOf(
                BroadcastPhase.PREPARING, BroadcastPhase.RECONNECTING, BroadcastPhase.RECOVERING)) {
            state.copy(phase = BroadcastPhase.CONNECTING, error = null)
        } else state
        BroadcastLifecycleEvent.Announced -> if (state.phase == BroadcastPhase.CONNECTING) {
            state.copy(phase = BroadcastPhase.ANNOUNCED)
        } else state
        BroadcastLifecycleEvent.RecordingLocally -> if (state.phase !in setOf(
                BroadcastPhase.IDLE, BroadcastPhase.STOPPING, BroadcastPhase.FAILED)) {
            state.copy(phase = BroadcastPhase.RECORDING, error = null)
        } else state
        is BroadcastLifecycleEvent.SubscriberDemand -> when {
            event.requested && state.phase in setOf(BroadcastPhase.ANNOUNCED, BroadcastPhase.WAITING_FOR_SUBSCRIBER, BroadcastPhase.RECOVERING) ->
                state.copy(phase = BroadcastPhase.PUBLISHING)
            !event.requested && state.phase in setOf(
                BroadcastPhase.ANNOUNCED, BroadcastPhase.PUBLISHING, BroadcastPhase.DEGRADED, BroadcastPhase.RECOVERING) ->
                state.copy(phase = BroadcastPhase.WAITING_FOR_SUBSCRIBER)
            else -> state
        }
        is BroadcastLifecycleEvent.HealthChanged -> when {
            event.degraded && state.phase == BroadcastPhase.PUBLISHING -> state.copy(phase = BroadcastPhase.DEGRADED)
            !event.degraded && state.phase == BroadcastPhase.DEGRADED -> state.copy(phase = BroadcastPhase.PUBLISHING)
            else -> state
        }
        BroadcastLifecycleEvent.RecoveryStarted -> if (state.phase in setOf(
                BroadcastPhase.ANNOUNCED, BroadcastPhase.WAITING_FOR_SUBSCRIBER, BroadcastPhase.PUBLISHING, BroadcastPhase.DEGRADED)) {
            state.copy(phase = BroadcastPhase.RECOVERING, error = null)
        } else state
        is BroadcastLifecycleEvent.RecoveryCompleted -> if (state.phase == BroadcastPhase.RECOVERING) {
            state.copy(phase = if (event.subscriberDemanded) BroadcastPhase.PUBLISHING else BroadcastPhase.WAITING_FOR_SUBSCRIBER)
        } else state
        BroadcastLifecycleEvent.Reconnecting -> if (state.phase !in setOf(BroadcastPhase.IDLE, BroadcastPhase.STOPPING, BroadcastPhase.FAILED)) {
            state.copy(phase = BroadcastPhase.RECONNECTING)
        } else state
        BroadcastLifecycleEvent.Recovered -> if (state.phase == BroadcastPhase.RECONNECTING) state.copy(phase = BroadcastPhase.RECOVERING) else state
        BroadcastLifecycleEvent.StopRequested -> if (state.phase !in setOf(BroadcastPhase.IDLE, BroadcastPhase.FAILED)) state.copy(phase = BroadcastPhase.STOPPING) else state
        is BroadcastLifecycleEvent.Failed -> state.copy(phase = BroadcastPhase.FAILED, error = event.message)
        BroadcastLifecycleEvent.CleanedUp -> state.copy(phase = BroadcastPhase.IDLE, error = null)
    }
}
