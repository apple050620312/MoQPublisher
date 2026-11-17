package com.sangege.moqpublisher

/** Publishing means a requested video track accepted frames, not that a viewer decoded them. */
enum class RelayConnectionPhase { CONNECTED, PUBLISHING, RECONNECTING }

internal fun RelayConnectionPhase.statusResource(): Int = when (this) {
    RelayConnectionPhase.CONNECTED -> R.string.status_connected
    RelayConnectionPhase.PUBLISHING -> R.string.status_publishing
    RelayConnectionPhase.RECONNECTING -> R.string.status_reconnecting
}

internal fun StreamState.isActive(): Boolean = this is StreamState.Connecting || this is StreamState.Live

internal object RelayConnectionPolicy {
    const val CONNECT_TIMEOUT_MS = 45_000L
    // Status changes are event-like; polling at 4 Hz is responsive to handovers
    // without turning the relay monitor into a busy loop while media is live.
    const val SESSION_STATUS_POLL_MS = 250L
    const val INITIAL_BACKOFF_US = 1_000_000uL
    const val MAX_BACKOFF_US = 30_000_000uL
    const val RETRY_WINDOW_US = 120_000_000uL

    fun mediaWritten(state: StreamState.Live, requested: Boolean): StreamState.Live =
        if (requested && state.phase == RelayConnectionPhase.CONNECTED) state.copy(phase = RelayConnectionPhase.PUBLISHING) else state

    fun reconnected(state: StreamState.Live, epoch: ULong, base: Long): StreamState.Live = state.copy(
        phase = RelayConnectionPhase.CONNECTED, epoch = epoch,
        reconnectCount = base + (epoch.coerceAtLeast(1u) - 1u).toLong(),
        rttMs = null, sendRateKbps = null
    )
}
