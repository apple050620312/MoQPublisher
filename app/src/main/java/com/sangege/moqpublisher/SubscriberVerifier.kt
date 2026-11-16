package com.sangege.moqpublisher

import android.media.MediaCodec
import android.media.MediaFormat
import dev.moq.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.moq.MoqException

sealed interface SubscriberVerification {
    data object Idle : SubscriberVerification
    data class Running(val stage: UiMessage) : SubscriberVerification
    data class Passed(val codec: String, val width: Int, val height: Int, val frames: Int, val audio: Boolean) : SubscriberVerification
    data class Failed(val message: UiMessage) : SubscriberVerification
}

/** An independent, subscribe-token-only remote session: never the publisher's loopback origin. */
internal class SubscriberVerifier(private val diagnostics: AppDiagnostics) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<SubscriberVerification>(SubscriberVerification.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    @Synchronized fun reset() { generation++; job?.cancel(); job = null; mutableState.value = SubscriberVerification.Idle }

    @Synchronized private fun report(ticket: Long, value: SubscriberVerification) {
        if (ticket == generation) mutableState.value = value
    }

    @Synchronized fun verify(profile: RelayProfile, expectAudio: Boolean) {
        if (!profile.canShare() || job?.isActive == true) return
        val ticket = ++generation
        DiagnosticRedactor.protectSecrets(profile.publishToken, profile.subscribeToken)
        mutableState.value = SubscriberVerification.Running(UiMessage(R.string.verify_connecting))
        job = scope.launch {
            try {
                val result = withTimeout(30_000) { receiveAndDecode(profile, expectAudio, ticket) }
                currentCoroutineContext().ensureActive()
                report(ticket, result)
                diagnostics.event("subscriber_decode_verified", mapOf("codec" to result.codec,
                    "width" to result.width, "height" to result.height, "frames" to result.frames, "audio" to result.audio))
            } catch (error: TimeoutCancellationException) {
                report(ticket, SubscriberVerification.Failed(UiMessage(R.string.verify_timeout)))
                diagnostics.event("subscriber_verification_timeout")
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                diagnostics.error("subscriber_verification_failed", error)
                report(ticket, SubscriberVerification.Failed(if (error is MoqException && error.isAuth)
                    UiMessage(R.string.error_relay_auth) else (error as? UiException)?.uiMessage ?: UiMessage(R.string.verify_failed)))
            }
        }
    }

    private suspend fun receiveAndDecode(profile: RelayProfile, expectAudio: Boolean, ticket: Long): SubscriberVerification.Passed = coroutineScope {
        // Distinct origins also make it impossible to mistake a local publication for remote delivery.
        OriginProducer(OriginConfig()).use { emptyPublish ->
            OriginProducer(OriginConfig()).use { remoteSubscribe ->
                Moq.connect(profile.subscribeUrl(), tlsVerify = true, reconnect = false, websocketEnabled = false,
                    publish = emptyPublish, subscribe = remoteSubscribe).use { moq ->
                    report(ticket, SubscriberVerification.Running(UiMessage(R.string.verify_catalog)))
                    moq.announcedBroadcast(profile.broadcastName.trim()).use { announcement ->
                        try {
                            announcement.available().use { broadcast ->
                                broadcast.subscribeCatalog().use { catalogs ->
                                    try {
                                        var catalog = checkNotNull(catalogs.next())
                                        while (catalog.video.isEmpty() || (expectAudio && catalog.audio.isEmpty()))
                                            catalog = checkNotNull(catalogs.next())
                                        val video = catalog.video.entries.first()
                                        report(ticket, SubscriberVerification.Running(UiMessage(R.string.verify_decoding)))
                                        val decodedAudio = async {
                                            if (!expectAudio) false else {
                                                val audio = catalog.audio.entries.first()
                                                broadcast.decodeAudio(audio.key, audio.value,
                                                    AudioDecoderOutput(AudioSampleFormat.S16, maxAgeUs = 500_000u)).use { pcm ->
                                                    try {
                                                        repeat(3) { check(checkNotNull(pcm.next()).data.isNotEmpty()) }
                                                        true
                                                    } finally { pcm.cancel() }
                                                }
                                            }
                                        }
                                        broadcast.subscribeMedia(video.key, video.value.container,
                                            Subscription(maxAgeUs = 500_000u)).use { frames ->
                                            try {
                                                val decoded = decodeVideo(video.value, frames)
                                                SubscriberVerification.Passed(video.value.codec, decoded.first, decoded.second, 3, decodedAudio.await())
                                            } finally { frames.cancel() }
                                        }
                                    } finally { catalogs.cancel() }
                                }
                            }
                        } finally { announcement.cancel() }
                    }
                }
            }
        }
    }

    /** Prove output frames exist, rather than accepting catalog, SUBSCRIBE_OK or compressed bytes. */
    private suspend fun decodeVideo(video: Video, frames: MediaConsumer): Pair<Int, Int> {
        val mime = when {
            video.codec.startsWith("avc") -> MediaFormat.MIMETYPE_VIDEO_AVC
            video.codec.startsWith("hev") || video.codec.startsWith("hvc") -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> throw UiException(UiMessage(R.string.verify_codec_unsupported))
        }
        val dimensions = video.coded ?: throw UiException(UiMessage(R.string.verify_codec_unsupported))
        val decoder = MediaCodec.createDecoderByType(mime)
        try {
            decoder.configure(MediaFormat.createVideoFormat(mime, dimensions.width.toInt(), dimensions.height.toInt()), null, null, 0)
            decoder.start()
            var started = false
            var decoded = 0
            val info = MediaCodec.BufferInfo()
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = checkNotNull(frames.next()) { "Remote video ended before decode verification" }
                if (frame.payload.isEmpty()) continue
                if (!started && !frame.keyframe) continue
                started = true
                val bytes = VideoDecoderInput.annexB(frame.payload)
                var input = decoder.dequeueInputBuffer(10_000)
                while (input < 0) {
                    currentCoroutineContext().ensureActive()
                    decoded += drainDecoder(decoder, info)
                    input = decoder.dequeueInputBuffer(10_000)
                }
                val buffer = checkNotNull(decoder.getInputBuffer(input))
                check(bytes.size <= buffer.remaining()) { "Remote video frame exceeds decoder input capacity" }
                buffer.put(bytes)
                decoder.queueInputBuffer(input, 0, bytes.size, frame.timestampUs.toLong(), 0)
                decoded += drainDecoder(decoder, info)
                if (decoded >= 3) {
                    val output = decoder.outputFormat
                    return output.getInteger(MediaFormat.KEY_WIDTH) to output.getInteger(MediaFormat.KEY_HEIGHT)
                }
            }
        } finally { runCatching { decoder.stop() }; decoder.release() }
    }

    private fun drainDecoder(decoder: MediaCodec, info: MediaCodec.BufferInfo): Int {
        var decoded = 0
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index >= 0) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) decoded++
                decoder.releaseOutputBuffer(index, false)
            } else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return decoded
        }
    }
}
