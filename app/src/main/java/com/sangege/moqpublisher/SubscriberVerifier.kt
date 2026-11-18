package com.sangege.moqpublisher

import android.media.MediaCodec
import android.media.MediaFormat
import dev.moq.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.moq.MoqException
import uniffi.moq.MoqMediaFrame

sealed interface SubscriberVerification {
    data object Idle : SubscriberVerification
    data class Running(val stage: UiMessage) : SubscriberVerification
    data class Passed(val codec: String, val width: Int, val height: Int, val frames: Int, val audio: Boolean) : SubscriberVerification
    data class Failed(val message: UiMessage) : SubscriberVerification
}

internal enum class SubscriberVerificationStage(val progress: Int, val failure: Int) {
    CONNECTION(R.string.verify_connecting, R.string.verify_connection_failed),
    AUTHENTICATION(R.string.verify_authentication, R.string.verify_authentication_failed),
    CATALOG(R.string.verify_catalog, R.string.verify_catalog_failed),
    SUBSCRIPTION(R.string.verify_subscription_stage, R.string.verify_track_failed),
    MEDIA(R.string.verify_receiving, R.string.verify_media_failed),
    VIDEO_DECODE(R.string.verify_video_decode, R.string.verify_video_decode_failed),
    AUDIO_DECODE(R.string.verify_audio_decode, R.string.verify_audio_decode_failed);

    fun failureMessage(error: Throwable): UiMessage = when {
        error is MoqException && error.isAuth -> UiMessage(R.string.verify_authentication_failed)
        error is UiException -> error.uiMessage
        else -> UiMessage(failure)
    }
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
        if (!profile.canSubscribe() || job?.isActive == true) return
        val ticket = ++generation
        DiagnosticRedactor.protectSecrets(profile.publishToken, profile.subscribeToken)
        mutableState.value = SubscriberVerification.Running(UiMessage(R.string.verify_connecting))
        job = scope.launch {
            var stage = SubscriberVerificationStage.CONNECTION
            try {
                val result = withTimeout(30_000) {
                    receiveAndDecode(profile, expectAudio) { next ->
                        stage = next
                        report(ticket, SubscriberVerification.Running(UiMessage(next.progress)))
                        diagnostics.event("subscriber_verification_stage", mapOf("provider" to profile.provider, "stage" to next))
                    }
                }
                currentCoroutineContext().ensureActive()
                report(ticket, result)
                diagnostics.event("subscriber_decode_verified", mapOf("codec" to result.codec,
                    "width" to result.width, "height" to result.height, "frames" to result.frames, "audio" to result.audio))
            } catch (error: TimeoutCancellationException) {
                report(ticket, SubscriberVerification.Failed(stage.failureMessage(error)))
                diagnostics.event("subscriber_verification_timeout", mapOf("stage" to stage))
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                val failedStage = if (error is MoqException && error.isAuth) SubscriberVerificationStage.AUTHENTICATION else stage
                diagnostics.event("subscriber_verification_failed", mapOf("stage" to failedStage, "error" to error.toString()))
                report(ticket, SubscriberVerification.Failed(failedStage.failureMessage(error)))
            }
        }
    }

    private suspend fun receiveAndDecode(
        profile: RelayProfile,
        expectAudio: Boolean,
        progress: (SubscriberVerificationStage) -> Unit
    ): SubscriberVerification.Passed {
        // Distinct origins also make it impossible to mistake a local publication for remote delivery.
        OriginProducer(OriginConfig()).use { emptyPublish ->
            OriginProducer(OriginConfig()).use { remoteSubscribe ->
                Moq.connect(profile.subscribeUrl(), tlsVerify = true, reconnect = false, websocketEnabled = false,
                    publish = emptyPublish, subscribe = remoteSubscribe).use { moq ->
                    // The SDK completes authentication as part of connect().
                    progress(SubscriberVerificationStage.AUTHENTICATION)
                    progress(SubscriberVerificationStage.CATALOG)
                    moq.announcedBroadcast(profile.broadcastName.trim()).use { announcement ->
                        try {
                            announcement.available().use { broadcast ->
                                broadcast.subscribeCatalog().use { catalogs ->
                                    try {
                                        var catalog = checkNotNull(catalogs.next())
                                        while (catalog.video.isEmpty() || (expectAudio && catalog.audio.isEmpty()))
                                            catalog = checkNotNull(catalogs.next())
                                        val video = catalog.video.entries.first()
                                        progress(SubscriberVerificationStage.SUBSCRIPTION)
                                        val decodedVideo = broadcast.subscribeMedia(video.key, video.value.container,
                                            Subscription(maxAgeUs = 500_000u)).use { frames ->
                                            try {
                                                progress(SubscriberVerificationStage.MEDIA)
                                                var firstKeyframe: MoqMediaFrame
                                                do {
                                                    firstKeyframe = checkNotNull(frames.next()) { "Remote video ended before a keyframe arrived" }
                                                } while (firstKeyframe.payload.isEmpty() || !firstKeyframe.keyframe)
                                                progress(SubscriberVerificationStage.VIDEO_DECODE)
                                                decodeVideo(video.value, frames, firstKeyframe)
                                            } finally { frames.cancel() }
                                        }
                                        if (expectAudio) {
                                            progress(SubscriberVerificationStage.AUDIO_DECODE)
                                            val audio = catalog.audio.entries.first()
                                            broadcast.decodeAudio(audio.key, audio.value,
                                                AudioDecoderOutput(AudioSampleFormat.S16, maxAgeUs = 500_000u)).use { pcm ->
                                                try {
                                                    repeat(3) { check(checkNotNull(pcm.next()).data.isNotEmpty()) }
                                                } finally { pcm.cancel() }
                                            }
                                        }
                                        return SubscriberVerification.Passed(
                                            video.value.codec, decodedVideo.first, decodedVideo.second, 3, expectAudio
                                        )
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
    private suspend fun decodeVideo(video: Video, frames: MediaConsumer, keyframe: MoqMediaFrame): Pair<Int, Int> {
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
            var frame = keyframe
            var decoded = 0
            val info = MediaCodec.BufferInfo()
            while (true) {
                currentCoroutineContext().ensureActive()
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
                do {
                    frame = checkNotNull(frames.next()) { "Remote video ended before decode verification" }
                } while (frame.payload.isEmpty())
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
