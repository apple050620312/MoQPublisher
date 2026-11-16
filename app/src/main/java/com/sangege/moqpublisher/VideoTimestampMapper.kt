package com.sangege.moqpublisher

/**
 * Maps CameraX sensor timestamps to the stream clock used by the audio track.
 *
 * Camera sensor clocks are monotonic, but their origin is unrelated to
 * System.nanoTime() and can change when the physical camera is switched.  A
 * callback arrival timestamp therefore makes video PTS jitter, while using the
 * raw sensor timestamp can make PTS jump by hours.  This mapper keeps the
 * sensor cadence and anchors the first frame to the broadcast clock.
 */
internal class VideoTimestampMapper(
    private val discontinuityNs: Long = 2_000_000_000L
) {
    data class Stamp(val timestampUs: Long, val reset: Boolean)

    private var sensorAnchorNs: Long? = null
    private var outputAnchorUs: Long = 0L
    private var lastSensorNs: Long? = null
    private var lastTimestampUs = -1L

    fun map(sensorTimestampNs: Long, callbackTimeNs: Long, streamStartNs: Long): Stamp {
        val sensorNs = sensorTimestampNs.takeIf { it > 0L } ?: callbackTimeNs
        val previousSensorNs = lastSensorNs
        val discontinuity = previousSensorNs != null &&
            (sensorNs < previousSensorNs || sensorNs - previousSensorNs > discontinuityNs)

        if (sensorAnchorNs == null || discontinuity) {
            sensorAnchorNs = sensorNs
            val callbackUs = ((callbackTimeNs - streamStartNs) / 1_000L).coerceAtLeast(0L)
            // A reset must remain monotonic for MediaCodec while still moving
            // close to the current capture time after a camera switch.
            outputAnchorUs = maxOf(callbackUs, lastTimestampUs + 1L)
        }

        val anchor = checkNotNull(sensorAnchorNs)
        val relativeUs = ((sensorNs - anchor) / 1_000L).coerceAtLeast(0L)
        val timestampUs = maxOf(outputAnchorUs + relativeUs, lastTimestampUs + 1L)
        lastSensorNs = sensorNs
        lastTimestampUs = timestampUs
        return Stamp(timestampUs, discontinuity)
    }

    fun reset() {
        sensorAnchorNs = null
        lastSensorNs = null
        outputAnchorUs = 0L
        lastTimestampUs = -1L
    }
}
