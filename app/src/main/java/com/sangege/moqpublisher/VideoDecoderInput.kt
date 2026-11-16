package com.sangege.moqpublisher

import java.io.ByteArrayOutputStream

/** AVC3/HEV1 inline parameter sets survive conversion to Android's Annex B decoder input. */
internal object VideoDecoderInput {
    fun annexB(payload: ByteArray): ByteArray {
        if (payload.size >= 3 && payload[0] == 0.toByte() && payload[1] == 0.toByte() &&
            (payload[2] == 1.toByte() || (payload.size >= 4 && payload[2] == 0.toByte() && payload[3] == 1.toByte()))) return payload
        val out = ByteArrayOutputStream(payload.size)
        var at = 0
        while (at < payload.size) {
            require(payload.size - at >= 4) { "Truncated video NAL length" }
            var length = 0L
            repeat(4) { length = (length shl 8) or (payload[at++].toLong() and 255) }
            require(length in 1..(payload.size - at).toLong()) { "Invalid video NAL length" }
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(payload, at, length.toInt())
            at += length.toInt()
        }
        return out.toByteArray()
    }
}
