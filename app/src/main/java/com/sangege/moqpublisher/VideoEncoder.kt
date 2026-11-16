package com.sangege.moqpublisher

internal interface VideoEncoder : AutoCloseable {
    val width: Int
    val height: Int
    val bitrateBps: Long
    val pendingFrames: Int
    fun setBitrate(bitrateBps: Long)
    fun requestKeyframe(timestampUs: Long)
}
