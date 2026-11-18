package com.sangege.moqpublisher

import dev.moq.isAuth
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException
import uniffi.moq.MoqException

/** Publishing means a requested video track accepted frames, not that a viewer decoded them. */
enum class RelayConnectionPhase { CONNECTED, PUBLISHING, RECONNECTING, RECOVERING }

internal fun RelayConnectionPhase.statusResource(): Int = when (this) {
    RelayConnectionPhase.CONNECTED -> R.string.status_connected
    RelayConnectionPhase.PUBLISHING -> R.string.status_publishing
    RelayConnectionPhase.RECONNECTING -> R.string.status_reconnecting
    RelayConnectionPhase.RECOVERING -> R.string.status_recovering_video
}

internal fun StreamState.isActive(): Boolean = this is StreamState.Connecting || this is StreamState.Live

internal fun StreamState.canVerifyRemoteMedia(): Boolean =
    this is StreamState.Live && !recordingOnly &&
        (phase == RelayConnectionPhase.CONNECTED || phase == RelayConnectionPhase.PUBLISHING)

internal fun StreamState.Live.statusResource(): Int = if (recordingOnly) R.string.recording_status else phase.statusResource()

internal fun StreamState.Live.acceptsMedia(enqueuedAtNs: Long): Boolean =
    !recordingOnly && phase != RelayConnectionPhase.RECONNECTING && (reconnectedAtNs == null || enqueuedAtNs >= reconnectedAtNs)

internal class RelayReconnectDeadline(private val retryMs: Long = RelayConnectionPolicy.RETRY_WINDOW_US.toLong() / 1_000L) {
    private var disconnectedAtMs: Long? = null

    fun expired(connected: Boolean, nowMs: Long): Boolean {
        if (connected) {
            disconnectedAtMs = null
            return false
        }
        val start = disconnectedAtMs ?: nowMs.also { disconnectedAtMs = it }
        return nowMs - start >= retryMs
    }
}

/** Bounds dial retries across default-network changes until a session is established. */
internal class RelayDialRetry(
    private val windowMs: Long = RelayConnectionPolicy.RETRY_WINDOW_US.toLong() / 1_000L
) {
    private var startedAtMs: Long? = null
    private var retries = 0

    fun timeoutMs(nowMs: Long): Long {
        val start = startedAtMs ?: nowMs.also { startedAtMs = it }
        val remaining = (windowMs - (nowMs - start)).coerceAtLeast(0)
        return minOf(RelayConnectionPolicy.CONNECT_TIMEOUT_MS, remaining)
    }

    fun delayAfterFailure(error: Exception, nowMs: Long): Long? {
        val transient = error is TimeoutCancellationException || error is UnknownHostException ||
            error is SocketTimeoutException || (error is MoqException.Transport && !error.isAuth)
        if (!transient || retries >= 3) return null
        val delayMs = 1_000L shl retries
        if (timeoutMs(nowMs) <= delayMs) return null
        retries++
        return delayMs
    }

    fun connected() {
        startedAtMs = null
        retries = 0
    }
}

internal object RelayConnectionPolicy {
    const val CONNECT_TIMEOUT_MS = 45_000L
    // Status changes are event-like; polling at 4 Hz is responsive to handovers
    // without turning the relay monitor into a busy loop while media is live.
    const val SESSION_STATUS_POLL_MS = 250L
    const val INITIAL_BACKOFF_US = 1_000_000uL
    const val MAX_BACKOFF_US = 30_000_000uL
    const val RETRY_WINDOW_US = 120_000_000uL

    fun mediaWritten(
        state: StreamState.Live, beforeWrite: StreamState.Live, requested: Boolean, keyframe: Boolean
    ): StreamState.Live {
        if (state.epoch != beforeWrite.epoch || state.reconnectCount != beforeWrite.reconnectCount ||
            state.phase == RelayConnectionPhase.RECONNECTING ||
            (state.phase == RelayConnectionPhase.RECOVERING && !keyframe)) return state
        val phase = if (requested) RelayConnectionPhase.PUBLISHING else RelayConnectionPhase.CONNECTED
        return if (state.phase == phase) state else state.copy(phase = phase)
    }

    fun reconnected(state: StreamState.Live, epoch: ULong, base: Long, nowNs: Long): StreamState.Live = state.copy(
        phase = RelayConnectionPhase.RECOVERING,
        epoch = epoch,
        reconnectCount = maxOf(state.reconnectCount + 1, base + (epoch.coerceAtLeast(1u) - 1u).toLong()),
        reconnectedAtNs = nowNs,
        rttMs = null,
        sendRateKbps = null,
        publishedFps = null
    )
}
