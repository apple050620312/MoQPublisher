package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class VideoDecoderInputTest {
    @Test fun keepsInlineH264AndH265AnnexBParameterSetsIntact() {
        for (header in listOf(0x67, 0x40)) {
            val frame = byteArrayOf(0, 0, 0, 1, header.toByte(), 1, 2, 3)
            assertArrayEquals(frame, VideoDecoderInput.annexB(frame))
        }
    }
    @Test fun convertsLengthPrefixedNalsWithoutLosingParametersOrFrameData() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 2, 0, 0, 0, 1, 0x65, 3, 4),
            VideoDecoderInput.annexB(byteArrayOf(0, 0, 0, 2, 0x67, 2, 0, 0, 0, 3, 0x65, 3, 4)))
    }
    @Test fun rejectsTruncatedOrOverflowingLengths() {
        for (bad in listOf(byteArrayOf(0, 0), byteArrayOf(0, 0, 0, 5, 1), byteArrayOf(-1, -1, -1, -1)))
            assertThrows(IllegalArgumentException::class.java) { VideoDecoderInput.annexB(bad) }
    }
}
