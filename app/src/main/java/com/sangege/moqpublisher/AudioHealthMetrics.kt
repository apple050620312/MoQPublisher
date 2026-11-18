package com.sangege.moqpublisher

data class AudioLevel(val peakPercent: Float, val rmsPercent: Float, val peakHoldPercent: Float, val clipping: Boolean)

/** Distinguishes microphone gaps, publisher bursts, silence, and network-independent queueing. */
internal class AudioHealthMetrics(private val nanoTime: () -> Long = System::nanoTime) {
    private class Cadence {
        var previous: Long? = null
        var count = 0L
        var total = 0L
        var min: Long? = null
        var max = 0L
        fun observe(now: Long) {
            previous?.let {
                val interval = (now - it).coerceAtLeast(0L)
                count++
                total += interval
                min = minOf(min ?: interval, interval)
                max = maxOf(max, interval)
            }
            previous = now
        }
        fun sample(prefix: String): Map<String, Any?> = mapOf(
            "${prefix}_interval_avg_ms" to if (count > 0) total / count / 1e6 else null,
            "${prefix}_interval_min_ms" to min?.div(1e6),
            "${prefix}_interval_max_ms" to if (count > 0) max / 1e6 else null
        ).also { count = 0; total = 0; min = null; max = 0 }
    }

    private var capture = Cadence()
    private var publication = Cadence()
    private var captured = 0
    private var silent = 0
    private var peak = 0
    private var rmsSumSquares = 0.0
    private var rmsSamples = 0L
    private var peakHold = 0
    private var clipping = false
    private var bytes = 0L
    private var groups = 0
    private var queueMax = 0
    private var unreadMs: Double? = null
    private var timestampAgeMs: Double? = null

    @Synchronized fun reset() {
        capture = Cadence(); publication = Cadence()
        captured = 0; silent = 0; peak = 0; rmsSumSquares = 0.0; rmsSamples = 0
        peakHold = 0; clipping = false; bytes = 0; groups = 0; queueMax = 0
        unreadMs = null; timestampAgeMs = null
    }

    @Synchronized fun captured(pcm: ByteArray) {
        capture.observe(nanoTime())
        captured++
        var framePeak = 0
        for (i in 0 until pcm.size - 1 step 2) {
            val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            framePeak = maxOf(framePeak, kotlin.math.abs(sample))
            rmsSumSquares += sample.toDouble() * sample.toDouble()
            rmsSamples++
            if (kotlin.math.abs(sample) >= Short.MAX_VALUE) clipping = true
        }
        if (framePeak == 0) silent++
        peak = maxOf(peak, framePeak)
        peakHold = maxOf(peakHold, framePeak)
    }

    @Synchronized fun level(): AudioLevel = AudioLevel(
        peakPercent = peak * 100f / Short.MAX_VALUE,
        rmsPercent = if (rmsSamples == 0L) 0f else (kotlin.math.sqrt(rmsSumSquares / rmsSamples) * 100.0 / Short.MAX_VALUE).toFloat(),
        peakHoldPercent = peakHold * 100f / Short.MAX_VALUE,
        clipping = clipping
    )

    @Synchronized fun published(size: Int, groupClosed: Boolean) {
        publication.observe(nanoTime())
        bytes += size
        if (groupClosed) groups++
    }

    @Synchronized fun queueDepth(depth: Int) { queueMax = maxOf(queueMax, depth) }

    @Synchronized fun hardwareClock(readFrames: Long, recordedFrames: Long, recordedAtNs: Long) {
        unreadMs = (recordedFrames - readFrames) * 1000.0 / AudioSettings.SAMPLE_RATE
        timestampAgeMs = (nanoTime() - recordedAtNs) / 1e6
    }

    @Synchronized fun sample(): Map<String, Any?> = (capture.sample("audio_capture") + publication.sample("audio_publish") + mapOf(
        "audio_capture_packets" to captured, "audio_pcm_silent_packets" to silent,
        "audio_pcm_peak_percent" to peak * 100.0 / 32768,
        "audio_encoded_bytes" to bytes, "audio_groups" to groups, "audio_queue_max_depth" to queueMax,
        "audio_record_unread_ms" to unreadMs, "audio_record_timestamp_age_ms" to timestampAgeMs
    )).also { captured = 0; silent = 0; peak = 0; bytes = 0; groups = 0; queueMax = 0 }
}
