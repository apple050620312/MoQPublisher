package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class RelayConnectionPolicyTest {
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
