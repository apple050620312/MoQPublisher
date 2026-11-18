package com.sangege.moqpublisher

enum class RecordingMode(val label: Int, val records: Boolean, val publishes: Boolean) {
    STREAM_ONLY(R.string.recording_stream_only, records = false, publishes = true),
    STREAM_AND_RECORD(R.string.recording_stream_and_record, records = true, publishes = true),
    RECORD_ONLY(R.string.recording_record_only, records = true, publishes = false)
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
    val error: UiMessage? = null
)

internal data class RecordingVideoFrame(
    val generation: Int,
    val codec: dev.moq.VideoCodec,
    val width: Int,
    val height: Int,
    val framerate: Int,
    val headers: ByteArray,
    val accessUnit: AnnexB.AccessUnit,
    val timestampUs: Long
) {
    fun matchesFormat(other: RecordingVideoFrame): Boolean =
        generation == other.generation && codec == other.codec && width == other.width &&
            height == other.height && framerate == other.framerate && headers.contentEquals(other.headers)
}

