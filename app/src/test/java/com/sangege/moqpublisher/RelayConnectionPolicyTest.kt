package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class RelayConnectionPolicyTest {
    @Test fun connectionAloneAndUnrequestedWritesDoNotClaimPublication() {
        val connected = StreamState.Live()
        assertEquals(RelayConnectionPhase.CONNECTED, RelayConnectionPolicy.mediaWritten(connected, false).phase)
        assertEquals(RelayConnectionPhase.PUBLISHING, RelayConnectionPolicy.mediaWritten(connected, true).phase)
    }

    @Test fun queuedWritesCannotOverwriteReconnectingStatus() {
        val down = StreamState.Live(phase = RelayConnectionPhase.RECONNECTING)
        assertEquals(down, RelayConnectionPolicy.mediaWritten(down, true))
        val restored = RelayConnectionPolicy.reconnected(down, 4u, 2)
        assertEquals(RelayConnectionPhase.CONNECTED, restored.phase)
        assertEquals(5L, restored.reconnectCount)
        assertNull(restored.rttMs)
        assertEquals(RelayConnectionPhase.PUBLISHING, RelayConnectionPolicy.mediaWritten(restored, true).phase)
    }

    @Test fun allInProgressPhasesKeepCameraServiceAndStopControlsActive() {
        assertTrue(StreamState.Connecting.isActive())
        for (phase in RelayConnectionPhase.entries) assertTrue(StreamState.Live(phase = phase).isActive())
        assertFalse(StreamState.Idle.isActive())
    }
}
