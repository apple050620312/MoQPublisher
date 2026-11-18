package com.sangege.moqpublisher

enum class RecordingMode(val label: Int) {
    STREAM_ONLY(R.string.recording_stream_only),
    STREAM_AND_RECORD(R.string.recording_stream_and_record)
}

data class RecordingPreferences(
    val mode: RecordingMode = RecordingMode.STREAM_ONLY,
    val segmentMinutes: Int = 15
) {
    companion object {
        val SEGMENT_OPTIONS = listOf(5, 15, 30, 60)

        fun normalizeSegmentMinutes(value: Int): Int =
            SEGMENT_OPTIONS.minByOrNull { kotlin.math.abs(it - value) } ?: 15
    }
}

data class RecordingState(
    val mode: RecordingMode = RecordingMode.STREAM_ONLY,
    val active: Boolean = false,
    val segmentIndex: Int = 0,
    val currentFile: String? = null,
    val lastFile: String? = null,
    val bytesWritten: Long = 0L,
    val availableBytes: Long? = null,
    val error: String? = null
)

internal data class RecordingVideoFrame(
    val codec: dev.moq.VideoCodec,
    val width: Int,
    val height: Int,
    val framerate: Int,
    val headers: ByteArray,
    val accessUnit: AnnexB.AccessUnit,
    val timestampUs: Long
)

