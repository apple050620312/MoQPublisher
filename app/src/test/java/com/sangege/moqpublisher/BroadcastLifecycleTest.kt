package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Test

class BroadcastLifecycleTest {
    @Test
    fun normalPublicationPathSeparatesAnnouncementAndSubscriberDemand() {
        var state = BroadcastLifecycleState()
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.StartRequested)
        assertEquals(BroadcastPhase.PREPARING, state.phase)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.Connecting)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.Announced)
        assertEquals(BroadcastPhase.ANNOUNCED, state.phase)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.SubscriberDemand(false))
        assertEquals(BroadcastPhase.WAITING_FOR_SUBSCRIBER, state.phase)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.SubscriberDemand(true))
        assertEquals(BroadcastPhase.PUBLISHING, state.phase)
    }

    @Test
    fun reconnectAndRecoveryDoNotClaimPublishingBeforeDemand() {
        var state = BroadcastLifecycleState(phase = BroadcastPhase.PUBLISHING)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.Reconnecting)
        assertEquals(BroadcastPhase.RECONNECTING, state.phase)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.Recovered)
        assertEquals(BroadcastPhase.RECOVERING, state.phase)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.Connecting)
        assertEquals(BroadcastPhase.CONNECTING, state.phase)
        assertEquals(BroadcastPhase.CONNECTING, BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.SubscriberDemand(true)).phase)
    }

    @Test
    fun mediaRecoveryReturnsToTheCorrectDemandState() {
        var state = BroadcastLifecycleState(phase = BroadcastPhase.PUBLISHING)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.RecoveryStarted)
        assertEquals(BroadcastPhase.RECOVERING, state.phase)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.RecoveryCompleted(false))
        assertEquals(BroadcastPhase.WAITING_FOR_SUBSCRIBER, state.phase)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.RecoveryStarted)
        state = BroadcastLifecyclePolicy.reduce(state, BroadcastLifecycleEvent.RecoveryCompleted(true))
        assertEquals(BroadcastPhase.PUBLISHING, state.phase)
    }

    @Test
    fun illegalTransitionsAndCleanupAreSafe() {
        val idle = BroadcastLifecycleState()
        assertEquals(idle, BroadcastLifecyclePolicy.reduce(idle, BroadcastLifecycleEvent.Announced))
        val failed = BroadcastLifecyclePolicy.reduce(idle, BroadcastLifecycleEvent.Failed(UiMessage(R.string.error_broadcast)))
        assertEquals(BroadcastPhase.FAILED, failed.phase)
        assertEquals(BroadcastPhase.IDLE, BroadcastLifecyclePolicy.reduce(failed, BroadcastLifecycleEvent.CleanedUp).phase)
    }
}
