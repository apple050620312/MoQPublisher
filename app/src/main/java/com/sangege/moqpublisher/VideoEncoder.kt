package com.sangege.moqpublisher

import android.media.MediaCodecInfo
import android.os.Build

internal interface VideoEncoder : AutoCloseable {
    val name: String?
    val width: Int
    val height: Int
    val bitrateBps: Long
    val pendingFrames: Int
    fun setBitrate(bitrateBps: Long)
    fun requestKeyframe(timestampUs: Long)
}

internal fun MediaCodecInfo.isHardwareVideoEncoder(): Boolean {
    if (Build.VERSION.SDK_INT >= 29) return isHardwareAccelerated
    val codecName = name.lowercase()
    return !codecName.startsWith("omx.google.") && !codecName.startsWith("c2.android.") &&
        "ffmpeg" !in codecName && "sw.encoder" !in codecName && ".sw." !in codecName
}
