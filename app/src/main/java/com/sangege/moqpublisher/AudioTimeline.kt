package com.sangege.moqpublisher

/** MoQ audio advances by sample count; re-anchor explicitly after capture loses time. */
internal class AudioTimeline(private val frameDurationUs: Long = 20_000L) {
    data class Stamp(val timestampUs: Long, val reset: Boolean)

    private var nextUs: Long? = null

    fun next(readCompletedUs: Long): Stamp {
        val capturedUs = (readCompletedUs - frameDurationUs).coerceAtLeast(0L)
        val expectedUs = nextUs
        // Small scheduling jitter must not reset the codec. A gap beyond five
        // packets, however, cannot be repaired by changing per-frame timestamps:
        // the native Opus producer ignores them after its first frame.
        val reset = expectedUs != null && capturedUs - expectedUs > frameDurationUs * 5
        val timestampUs = if (expectedUs == null || reset) capturedUs else expectedUs
        nextUs = timestampUs + frameDurationUs
        return Stamp(timestampUs, reset)
    }
}
