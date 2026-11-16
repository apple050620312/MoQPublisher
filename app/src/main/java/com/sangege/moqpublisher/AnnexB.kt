package com.sangege.moqpublisher

/** Normalizes MediaCodec output and repeats codec headers at every independently decodable group. */
internal class AnnexB(private val hevc: Boolean) {
    private val headers = linkedMapOf<Int, ByteArray>()
    private val headerTypes = if (hevc) listOf(32, 33, 34) else listOf(7, 8)

    data class AccessUnit(val data: ByteArray, val keyframe: Boolean)

    fun configure(data: ByteArray) {
        for (nal in split(data)) {
            val type = type(nal)
            if (type in headerTypes) headers[type] = nal
        }
    }

    fun accessUnit(data: ByteArray): AccessUnit? {
        val nals = split(data)
        val types = nals.map { type(it) }
        for ((index, type) in types.withIndex()) {
            if (type in headerTypes) headers[type] = nals[index]
        }
        val picture = types.any { if (hevc) it in 0..31 else it in 1..5 }
        if (!picture) return null

        val keyframe = types.any { if (hevc) it in 16..23 else it == 5 }
        val payload = if (keyframe) {
            check(headerTypes.all { it in headers }) { "Encoder keyframe is missing codec parameter sets" }
            // Some codecs repeat only part of the configuration. Always send
            // VPS/SPS/PPS in decoding order, after a leading access unit delimiter.
            val delimiter = if (hevc) 35 else 9
            val leading = nals.takeWhile { type(it) == delimiter }
            leading + headerTypes.map { headers.getValue(it) } +
                nals.drop(leading.size).filterNot { type(it) in headerTypes }
        } else {
            nals
        }
        return AccessUnit(join(payload), keyframe)
    }

    fun configuration(): ByteArray = join(headerTypes.mapNotNull { headers[it] })

    private fun type(nal: ByteArray): Int =
        if (hevc) (nal[0].toInt() ushr 1) and 0x3f else nal[0].toInt() and 0x1f

    companion object {
        private val startCode = byteArrayOf(0, 0, 0, 1)

        private fun join(nals: List<ByteArray>): ByteArray {
            val out = ByteArray(nals.sumOf { it.size + startCode.size })
            var offset = 0
            for (nal in nals) {
                startCode.copyInto(out, offset)
                offset += startCode.size
                nal.copyInto(out, offset)
                offset += nal.size
            }
            return out
        }

        // Android emits Annex B for AVC/HEVC. Also accept 4-byte NAL lengths from
        // vendor codecs, converting them before MoQ's avc3/hev1 importer sees them.
        private fun split(data: ByteArray): List<ByteArray> {
            if (data.isEmpty()) return emptyList()
            fun startSize(at: Int): Int = when {
                at + 3 <= data.size && data[at] == 0.toByte() && data[at + 1] == 0.toByte() && data[at + 2] == 1.toByte() -> 3
                at + 4 <= data.size && data[at] == 0.toByte() && data[at + 1] == 0.toByte() && data[at + 2] == 0.toByte() && data[at + 3] == 1.toByte() -> 4
                else -> 0
            }

            if (startSize(0) > 0) {
                val nals = mutableListOf<ByteArray>()
                var start = startSize(0)
                var at = start
                while (at < data.size) {
                    val size = startSize(at)
                    if (size == 0) {
                        at++
                    } else {
                        if (at > start) nals += data.copyOfRange(start, at)
                        start = at + size
                        at = start
                    }
                }
                if (start < data.size) nals += data.copyOfRange(start, data.size)
                return nals
            }

            val nals = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < data.size) {
                require(data.size - offset >= 4) { "Invalid encoder NAL length" }
                var size = 0L
                repeat(4) { size = (size shl 8) or (data[offset++].toLong() and 0xff) }
                require(size in 1..(data.size - offset).toLong()) { "Invalid encoder NAL size" }
                val end = offset + size.toInt()
                nals += data.copyOfRange(offset, end)
                offset = end
            }
            return nals
        }
    }
}
