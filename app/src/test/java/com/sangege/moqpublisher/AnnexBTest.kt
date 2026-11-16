package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class AnnexBTest {
    private val sps = byteArrayOf(0x67, 0x42, 0, 0x1f)
    private val pps = byteArrayOf(0x68, 0x01)
    private val vps265 = byteArrayOf(0x40, 0x01, 0x0c)
    private val sps265 = byteArrayOf(0x42, 0x01, 0x01)
    private val pps265 = byteArrayOf(0x44, 0x01, 0x01)

    @Test
    fun h264KeyframesRepeatSpsAndPpsForLateJoiningViewers() {
        val packetizer = AnnexB(false)
        packetizer.configure(annex(sps))
        packetizer.configure(annex(pps))
        val idr = byteArrayOf(0x65, 0x12, 0x34)
        repeat(2) {
            val unit = packetizer.accessUnit(annex(idr))!!
            assertTrue(unit.keyframe)
            assertArrayEquals(annex(sps, pps, idr), unit.data)
        }
    }

    @Test
    fun h265KeyframesRepeatVpsSpsAndPps() {
        val packetizer = AnnexB(true)
        packetizer.configure(annex(vps265, sps265, pps265))
        for (type in listOf(19, 20, 21)) {
            val picture = byteArrayOf((type shl 1).toByte(), 1, 0x12)
            val unit = packetizer.accessUnit(annex(picture))!!
            assertTrue(unit.keyframe)
            assertArrayEquals(annex(vps265, sps265, pps265, picture), unit.data)
        }
    }

    @Test
    fun keyframeCarryingItsOwnHeadersIsNotDuplicated() {
        for ((hevc, headers, idr) in listOf(
            Triple(false, listOf(sps, pps), byteArrayOf(0x65, 1)),
            Triple(true, listOf(vps265, sps265, pps265), byteArrayOf(0x26, 1, 1))
        )) {
            val packetizer = AnnexB(hevc)
            val packet = annex(*(headers + listOf(idr)).toTypedArray())
            assertArrayEquals(packet, packetizer.accessUnit(packet)!!.data)
            assertArrayEquals(annex(*headers.toTypedArray()), packetizer.configuration())
        }
    }

    @Test
    fun partialInlineHeadersKeepParameterSetsInDecodingOrderAfterDelimiter() {
        for ((hevc, headers, picture) in listOf(
            Triple(false, listOf(sps, pps), byteArrayOf(0x65, 1)),
            Triple(true, listOf(vps265, sps265, pps265), byteArrayOf(0x26, 1, 1))
        )) {
            val packetizer = AnnexB(hevc)
            packetizer.configure(annex(*headers.toTypedArray()))
            val delimiter = if (hevc) byteArrayOf(0x46, 1, 0x50) else byteArrayOf(0x09, 0x10)
            val input = annex(delimiter, headers[1], picture)
            val expected = annex(*(listOf(delimiter) + headers + listOf(picture)).toTypedArray())
            assertArrayEquals(expected, packetizer.accessUnit(input)!!.data)
        }
    }

    @Test
    fun deltaFramesDoNotGetExtraHeaders() {
        val packetizer = AnnexB(false)
        packetizer.configure(annex(sps, pps))
        val delta = annex(byteArrayOf(0x41, 0x11))
        val unit = packetizer.accessUnit(delta)!!
        assertFalse(unit.keyframe)
        assertArrayEquals(delta, unit.data)
    }

    @Test
    fun threeByteStartCodesAndEscapedPayloadArePreserved() {
        val packetizer = AnnexB(false)
        packetizer.configure(annex(sps, pps))
        val idr = byteArrayOf(0x65, 0, 0, 3, 1, 0x12)
        val unit = packetizer.accessUnit(byteArrayOf(0, 0, 1) + idr)!!
        assertArrayEquals(annex(sps, pps, idr), unit.data)
    }

    @Test
    fun vendorLengthPrefixedAvcAndHevcBecomeAnnexB() {
        for ((hevc, headers, idr) in listOf(
            Triple(false, listOf(sps, pps), byteArrayOf(0x65, 1)),
            Triple(true, listOf(vps265, sps265, pps265), byteArrayOf(0x26, 1, 1))
        )) {
            val packetizer = AnnexB(hevc)
            packetizer.configure(lengthPrefixed(*headers.toTypedArray()))
            assertArrayEquals(annex(*(headers + listOf(idr)).toTypedArray()), packetizer.accessUnit(lengthPrefixed(idr))!!.data)
        }
    }

    @Test
    fun codecHeadersAloneAreNotPublishedAsPictures() {
        val packetizer = AnnexB(true)
        assertNull(packetizer.accessUnit(annex(vps265, sps265, pps265)))
        assertArrayEquals(annex(vps265, sps265, pps265), packetizer.configuration())
    }

    @Test
    fun replacementParameterSetsAreUsedAtTheNextKeyframe() {
        val packetizer = AnnexB(false)
        packetizer.configure(annex(sps, pps))
        val newSps = byteArrayOf(0x67, 0x42, 0, 0x28)
        packetizer.configure(annex(newSps, pps))
        val idr = byteArrayOf(0x65, 0x12)
        assertArrayEquals(annex(newSps, pps, idr), packetizer.accessUnit(annex(idr))!!.data)
    }

    @Test(expected = IllegalStateException::class)
    fun refusesUndecodableHevcKeyframeWithoutParameterSets() {
        AnnexB(true).accessUnit(annex(byteArrayOf(0x26, 1, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedNalLengths() {
        AnnexB(false).accessUnit(byteArrayOf(0, 0, 0, 99, 0x65, 1))
    }

    private fun annex(vararg nals: ByteArray): ByteArray =
        nals.fold(byteArrayOf()) { result, nal -> result + byteArrayOf(0, 0, 0, 1) + nal }

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray =
        nals.fold(byteArrayOf()) { result, nal ->
            result + byteArrayOf((nal.size ushr 24).toByte(), (nal.size ushr 16).toByte(), (nal.size ushr 8).toByte(), nal.size.toByte()) + nal
        }
}
