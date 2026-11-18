package com.sangege.moqpublisher

import kotlin.math.roundToInt
import java.util.concurrent.CancellationException

enum class AudioChannel(val count: Int, val bitrateBps: Long) {
    MONO(1, 64_000L), STEREO(2, 128_000L);

    companion object {
        fun fromPreference(value: String?): AudioChannel = entries.firstOrNull { it.name == value } ?: MONO
    }
}

enum class AudioQuality(private val monoKbps: Int, private val stereoKbps: Int) {
    ECONOMY(32, 64), STANDARD(64, 128), HIGH(96, 192), MAXIMUM(128, 256);

    fun bitrateBps(channel: AudioChannel): Long =
        (if (channel == AudioChannel.MONO) monoKbps else stereoKbps) * 1_000L

    companion object {
        fun fromPreference(value: String?): AudioQuality = entries.firstOrNull { it.name == value } ?: STANDARD
    }
}

internal object AudioSettings {
    const val SAMPLE_RATE = 48_000
    const val SAMPLES_PER_FRAME = 960 // 20 ms, per channel.
    const val DEFAULT_GAIN = 100
    const val MAX_GAIN = 300
    const val GAIN_RAMP_STEP_PERCENT = 10

    fun frameBytes(channel: AudioChannel): Int = SAMPLES_PER_FRAME * channel.count * 2
    fun normalizeGain(percent: Int): Int = percent.coerceIn(0, MAX_GAIN)

    /** Move one 20 ms audio frame toward a new gain without an audible step. */
    fun rampGain(current: Int, target: Int, step: Int = GAIN_RAMP_STEP_PERCENT): Int {
        val from = normalizeGain(current)
        val to = normalizeGain(target)
        val amount = step.coerceAtLeast(1)
        return when {
            from < to -> (from + amount).coerceAtMost(to)
            from > to -> (from - amount).coerceAtLeast(to)
            else -> from
        }
    }

    /** Return the format that really opened, so AudioRecord and Opus always agree. */
    fun <T> openCapture(
        requested: AudioChannel,
        open: (AudioChannel) -> T,
        onStereoFailure: (Exception) -> Unit = {}
    ): Pair<AudioChannel, T> = try {
        requested to open(requested)
    } catch (error: Exception) {
        if (requested != AudioChannel.STEREO || error is SecurityException || error is CancellationException) throw error
        onStereoFailure(error)
        AudioChannel.MONO to open(AudioChannel.MONO)
    }

    /** Apply gain to interleaved S16LE PCM before Opus encoding, saturating instead of wrapping. */
    fun applyGain(pcm: ByteArray, percent: Int) {
        require(pcm.size % 2 == 0)
        val gain = normalizeGain(percent)
        if (gain == DEFAULT_GAIN) return
        if (gain == 0) {
            pcm.fill(0)
            return
        }
        for (offset in pcm.indices step 2) {
            val sample = ((pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()
            val amplified = (sample * (gain / 100.0)).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[offset] = amplified.toByte()
            pcm[offset + 1] = (amplified shr 8).toByte()
        }
    }
}

data class AudioCaptureStatus(
    val requested: AudioChannel, val actual: AudioChannel, val gainPercent: Int,
    val bitrateBps: Long = actual.bitrateBps
) {
    val fellBackToMono: Boolean get() = requested == AudioChannel.STEREO && actual == AudioChannel.MONO
}
