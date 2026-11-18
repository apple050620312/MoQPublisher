package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test
import uniffi.moq.MoqErrorScope
import uniffi.moq.MoqException
import uniffi.moq.MoqProtocolError
import uniffi.moq.MoqProtocolKind

class RelayConnectionPolicyTest {
    @Test
    fun transientDialErrorsRetryWithBoundedBackoffAndResetAfterConnection() {
        val retry = RelayDialRetry()
        val dns = java.net.UnknownHostException()
        val transport = MoqException.Transport("relay unavailable")
        assertEquals(45_000L, retry.timeoutMs(0))
        assertEquals(1_000L, retry.delayAfterFailure(dns, 100))
        assertEquals(2_000L, retry.delayAfterFailure(transport, 1_200))
        assertEquals(4_000L, retry.delayAfterFailure(transport, 3_200))
        assertNull(retry.delayAfterFailure(transport, 7_200))
        retry.connected()
        assertEquals(45_000L, retry.timeoutMs(180_000))
        assertEquals(1_000L, retry.delayAfterFailure(dns, 180_200))
    }

    @Test
    fun exhaustedWindowCapsDialTimeAndAuthenticationRejectionEndsImmediately() {
        val retry = RelayDialRetry(windowMs = 5_000)
        assertEquals(5_000L, retry.timeoutMs(10_000))
        assertEquals(800L, retry.timeoutMs(14_200))
        assertNull(retry.delayAfterFailure(MoqException.Transport("closed"), 14_200))
        assertEquals(0L, retry.timeoutMs(15_000))
        retry.connected()
        val denied = MoqException.Protocol(MoqProtocolError(MoqErrorScope.SESSION, 2u, MoqProtocolKind.UNAUTHORIZED, "denied"))
        assertNull(retry.delayAfterFailure(denied, 16_000))
        assertNull(retry.delayAfterFailure(MoqException.Url("invalid"), 16_000))
    }
    @Test
    fun reconnectWindowStartsOverAfterAWorkingConnection() {
        val deadline = RelayReconnectDeadline(retryMs = 120_000)
        assertFalse(deadline.expired(false, 1_000))
        assertFalse(deadline.expired(false, 100_000))
        assertFalse(deadline.expired(true, 110_000))
        assertFalse(deadline.expired(false, 120_000))
        assertFalse(deadline.expired(false, 239_999))
        assertTrue(deadline.expired(false, 240_000))
    }

    @Test
    fun addressReplacementTriggersRedialOnTheSameDefaultNetwork() {
        val before = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
        val after = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 3))
        assertFalse(defaultNetworkAddressReplaced(emptySet(), setOf(before)))
        assertFalse(defaultNetworkAddressReplaced(setOf(before), setOf(before, after)))
        assertTrue(defaultNetworkAddressReplaced(setOf(before), setOf(after)))
    }

    @Test fun sessionStatusPollingIsResponsiveWithoutBusyLooping() {
        assertTrue(RelayConnectionPolicy.SESSION_STATUS_POLL_MS in 100L..1_000L)
    }

    @Test fun connectionAloneAndUnrequestedWritesDoNotClaimPublication() {
        val connected = StreamState.Live()
        assertEquals(RelayConnectionPhase.CONNECTED, RelayConnectionPolicy.mediaWritten(connected, connected, false, true).phase)
        val publishing = RelayConnectionPolicy.mediaWritten(connected, connected, true, false)
        assertEquals(RelayConnectionPhase.PUBLISHING, publishing.phase)
        assertEquals(RelayConnectionPhase.CONNECTED, RelayConnectionPolicy.mediaWritten(publishing, publishing, false, false).phase)
    }

    @Test fun recoveringStreamRequiresAFreshKeyframeBeforeReportingPublication() {
        val old = StreamState.Live()
        val down = StreamState.Live(phase = RelayConnectionPhase.RECONNECTING)
        assertEquals(down, RelayConnectionPolicy.mediaWritten(down, old, true, true))
        val restored = RelayConnectionPolicy.reconnected(down, 4u, 2, nowNs = 100)
        assertEquals(RelayConnectionPhase.RECOVERING, restored.phase)
        assertEquals(5L, restored.reconnectCount)
        assertNull(restored.rttMs)
        assertFalse(restored.canVerifyRemoteMedia())
        assertFalse(restored.acceptsMedia(99))
        assertTrue(restored.acceptsMedia(100))
        assertEquals(restored, RelayConnectionPolicy.mediaWritten(restored, old, true, true))
        assertEquals(restored, RelayConnectionPolicy.mediaWritten(restored, restored, true, false))
        val live = RelayConnectionPolicy.mediaWritten(restored, restored, true, true)
        assertEquals(RelayConnectionPhase.PUBLISHING, live.phase)
        assertTrue(live.canVerifyRemoteMedia())
    }

    @Test fun reconnectWithTheSameEpochAlsoRejectsInFlightWrites() {
        val before = StreamState.Live(epoch = 1u, reconnectCount = 3L)
        val after = RelayConnectionPolicy.reconnected(before, 1u, 3, nowNs = 100)
        assertEquals(4L, after.reconnectCount)
        assertEquals(after, RelayConnectionPolicy.mediaWritten(after, before, true, true))
        assertEquals(RelayConnectionPhase.CONNECTED, RelayConnectionPolicy.mediaWritten(after, after, false, true).phase)
    }

    @Test fun allInProgressPhasesKeepCameraServiceAndStopControlsActive() {
        assertTrue(StreamState.Connecting.isActive())
        for (phase in RelayConnectionPhase.entries) assertTrue(StreamState.Live(phase = phase).isActive())
        assertFalse(StreamState.Idle.isActive())
    }
}
