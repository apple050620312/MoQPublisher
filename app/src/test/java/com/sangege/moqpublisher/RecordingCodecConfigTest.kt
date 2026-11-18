package com.sangege.moqpublisher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCodecConfigTest {
    private fun nal(type: Int) = byteArrayOf(0, 0, 0, 1, type.toByte(), 42)

    @Test
    fun avcMuxerGetsSeparateStartCodedSpsAndPps() {
        val sps = nal(0x67)
        val pps = nal(0x68)
        val blocks = AnnexB.mp4CodecSpecificData(pps + sps, hevc = false)
        assertEquals(2, blocks.size)
        assertArrayEquals(sps, blocks[0])
        assertArrayEquals(pps, blocks[1])
    }

    @Test
    fun hevcMuxerGetsOneBlockInVpsSpsPpsOrder() {
        val vps = nal(32 shl 1)
        val sps = nal(33 shl 1)
        val pps = nal(34 shl 1)
        val blocks = AnnexB.mp4CodecSpecificData(sps + pps + vps, hevc = true)
        assertEquals(1, blocks.size)
        assertArrayEquals(vps + sps + pps, blocks.single())
    }
}
