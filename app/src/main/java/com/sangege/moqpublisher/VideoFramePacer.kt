package com.sangege.moqpublisher

/** Drops surplus camera frames before copying pixels or calling the synchronous encoder. */
internal class VideoFramePacer(framerate: Int) {
    init {
        require(framerate in 1..120)
    }

    private val intervalNanos = 1_000_000_000L / framerate
    // Camera callback timing varies slightly, even when capture is at the target FPS.
    private val jitterNanos = intervalNanos / 20
    private val discontinuityNanos = 2_000_000_000L
    private var nextFrameNanos: Long? = null
    private var lastFrameNanos: Long? = null

    // Called under the video lock. Keep a cadence instead of sleeping or queuing frames.
    fun shouldEncode(timeNanos: Long): Boolean {
        // Switching physical cameras can change the sensor clock origin.
        if (lastFrameNanos?.let { timeNanos < it || timeNanos - it > discontinuityNanos } == true) {
            // Camera switches and long sensor pauses must not produce a
            // catch-up burst or leave the pacer waiting on the old clock.
            nextFrameNanos = null
        }
        lastFrameNanos = timeNanos
        val next = nextFrameNanos
        if (next != null && timeNanos < next - jitterNanos) return false

        nextFrameNanos = if (next == null || timeNanos - next >= intervalNanos) {
            // A pause earns no extra frames: resume from now without a catch-up burst.
            timeNanos + intervalNanos
        } else {
            next + intervalNanos
        }
        return true
    }
}
