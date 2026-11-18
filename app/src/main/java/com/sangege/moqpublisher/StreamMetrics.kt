package com.sangege.moqpublisher

/** Tiny in-memory counters on capture threads; only the once-per-second sampler writes to disk. */
internal class StreamMetrics(private val nanoTime: () -> Long = System::nanoTime) {
    enum class Counter {
        CAMERA, PACED_DROP, BUSY_DROP, VIDEO_QUEUE_DROP, VIDEO_TIMESTAMP_RESET, AUDIO_QUEUE_DROP,
        SUBMITTED, ENCODED, KEYFRAME, PUBLISHED, VIDEO_BYTES, AUDIO_PACKET, AUDIO_RESET
    }
    enum class Stage(val field: String) {
        PIXEL_COPY("pixel_copy"), ENCODER_INPUT("encoder_input"),
        ENCODER_DRAIN("encoder_drain"), MOQ_WRITE("moq_write"), AUDIO_WRITE("audio_write"),
        AUDIO_QUEUE_WAIT("audio_queue_wait"), VIDEO_CALLBACK("video_callback"), GPU_SUBMIT("gpu_submit")
    }

    private val counts = LongArray(Counter.entries.size)
    private var sinceNs = nanoTime()
    private var lastCameraNs: Long? = null
    private var lastEncodedNs: Long? = null
    private var lastVideoNs: Long? = null
    private var lastAudioNs: Long? = null
    private var handoffTotalUs = 0L
    private var handoffMaxUs = 0L
    private var videoQueueMaxDepth = 0
    private val stageCounts = LongArray(Stage.entries.size)
    private val stageTotalsNs = LongArray(Stage.entries.size)
    private val stageMaxNs = LongArray(Stage.entries.size)

    @Synchronized
    fun timing(stage: Stage, durationNs: Long) {
        val index = stage.ordinal
        val duration = durationNs.coerceAtLeast(0L)
        stageCounts[index]++
        stageTotalsNs[index] += duration
        stageMaxNs[index] = maxOf(stageMaxNs[index], duration)
    }

    private fun resetTimings() {
        stageCounts.fill(0)
        stageTotalsNs.fill(0)
        stageMaxNs.fill(0)
    }

    @Synchronized
    fun reset() {
        counts.fill(0)
        sinceNs = nanoTime()
        lastCameraNs = null
        lastEncodedNs = null
        lastVideoNs = null
        lastAudioNs = null
        handoffTotalUs = 0L
        handoffMaxUs = 0L
        videoQueueMaxDepth = 0
        resetTimings()
    }

    @Synchronized
    fun count(counter: Counter) {
        counts[counter.ordinal]++
        val now = nanoTime()
        when (counter) {
            Counter.CAMERA -> lastCameraNs = now
            Counter.ENCODED -> lastEncodedNs = now
            Counter.AUDIO_PACKET -> lastAudioNs = now
            else -> Unit
        }
    }

    @Synchronized
    fun videoQueueDepth(depth: Int) {
        videoQueueMaxDepth = maxOf(videoQueueMaxDepth, depth.coerceAtLeast(0))
    }

    @Synchronized
    fun published(timestampUs: Long, streamStartNs: Long, bytes: Int) {
        counts[Counter.PUBLISHED.ordinal]++
        counts[Counter.VIDEO_BYTES.ordinal] += bytes
        val now = nanoTime()
        lastVideoNs = now
        val latencyUs = ((now - streamStartNs) / 1_000L - timestampUs).coerceAtLeast(0L)
        handoffTotalUs += latencyUs
        handoffMaxUs = maxOf(handoffMaxUs, latencyUs)
    }

    @Synchronized
    fun sample(): Map<String, Any?> {
        val now = nanoTime()
        val intervalNs = (now - sinceNs).coerceAtLeast(1L)
        fun count(counter: Counter) = counts[counter.ordinal]
        fun fps(counter: Counter) = String.format(java.util.Locale.US, "%.2f", count(counter) * 1e9 / intervalNs)
        fun age(last: Long?) = last?.let { (now - it).coerceAtLeast(0L) / 1_000_000L }
        val sample = linkedMapOf<String, Any?>(
            "interval_ms" to intervalNs / 1_000_000L,
            "camera_fps" to fps(Counter.CAMERA),
            "submitted_fps" to fps(Counter.SUBMITTED),
            "encoded_fps" to fps(Counter.ENCODED),
            "published_fps" to fps(Counter.PUBLISHED),
            "video_media_kbps" to count(Counter.VIDEO_BYTES) * 8_000_000.0 / intervalNs,
            "paced_drops" to count(Counter.PACED_DROP),
            "encoder_busy_drops" to count(Counter.BUSY_DROP),
            "video_queue_drops" to count(Counter.VIDEO_QUEUE_DROP),
            "video_queue_max_depth" to videoQueueMaxDepth,
            "video_timestamp_resets" to count(Counter.VIDEO_TIMESTAMP_RESET),
            "audio_queue_drops" to count(Counter.AUDIO_QUEUE_DROP),
            "keyframes" to count(Counter.KEYFRAME),
            "audio_packets" to count(Counter.AUDIO_PACKET),
            "audio_resets" to count(Counter.AUDIO_RESET),
            "video_handoff_avg_ms" to if (count(Counter.PUBLISHED) > 0) handoffTotalUs / count(Counter.PUBLISHED) / 1_000.0 else null,
            "video_handoff_max_ms" to if (count(Counter.PUBLISHED) > 0) handoffMaxUs / 1_000.0 else null,
            "last_camera_age_ms" to age(lastCameraNs),
            "last_encoded_age_ms" to age(lastEncodedNs),
            "last_video_age_ms" to age(lastVideoNs),
            "last_audio_age_ms" to age(lastAudioNs)
        )
        for (stage in Stage.entries) {
            val index = stage.ordinal
            val samples = stageCounts[index]
            sample["${stage.field}_avg_ms"] = if (samples > 0) stageTotalsNs[index] / samples / 1_000_000.0 else null
            sample["${stage.field}_max_ms"] = if (samples > 0) stageMaxNs[index] / 1_000_000.0 else null
        }
        counts.fill(0)
        handoffTotalUs = 0L
        handoffMaxUs = 0L
        videoQueueMaxDepth = 0
        resetTimings()
        sinceNs = now
        return sample
    }
}
