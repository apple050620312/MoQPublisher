package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSettingsTest {
    @Test
    fun segmentLengthSnapsToSupportedValues() {
        assertEquals(5, RecordingPreferences.normalizeSegmentMinutes(1))
        assertEquals(15, RecordingPreferences.normalizeSegmentMinutes(17))
        assertEquals(30, RecordingPreferences.normalizeSegmentMinutes(31))
        assertEquals(60, RecordingPreferences.normalizeSegmentMinutes(90))
    }

    @Test
    fun streamOnlyDoesNotRequestARecorderWorker() {
        assertEquals(RecordingMode.STREAM_ONLY, RecordingPreferences().mode)
        assertEquals(listOf(5, 15, 30, 60), RecordingPreferences.SEGMENT_OPTIONS)
    }
}
