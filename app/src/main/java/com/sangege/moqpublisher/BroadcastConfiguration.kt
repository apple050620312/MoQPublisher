package com.sangege.moqpublisher

import android.util.Size
import dev.moq.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class VideoMode(
    val size: Size,
    val fps: Int,
    val camera: CameraVideoSettings = CameraVideoSettings()
)

internal data class CameraVideoSettings(
    val lensId: String? = null,
    val stabilize: Boolean = false,
    val direction: VideoDirection = VideoDirection.FIXED_AT_START,
    val mirrorFront: Boolean = false
)

internal class LiveVideoModeChange(val rebuildEncoder: Boolean = false) {
    enum class Result { PENDING, APPLIED, FAILED }

    private val mutableResult = MutableStateFlow(Result.PENDING)
    val result = mutableResult.asStateFlow()

    fun finish(applied: Boolean) {
        mutableResult.compareAndSet(Result.PENDING, if (applied) Result.APPLIED else Result.FAILED)
    }
}

/** Immutable start snapshot. UI preference changes cannot mutate a running session. */
internal data class BroadcastConfiguration(
    val relayUrl: String,
    val streamPath: String,
    val tlsVerify: Boolean,
    val enableAudio: Boolean,
    val audioChannel: AudioChannel,
    val audioQuality: AudioQuality,
    val audioGainPercent: Int,
    val codec: VideoCodec,
    val framerate: Int,
    val videoBitrate: VideoBitrateConfiguration,
    val recording: RecordingPreferences = RecordingPreferences(),
    val provider: RelayProvider = RelayProvider.MOQ_PRO,
    val protocolDraft: Int? = null,
    val screen: ScreenCaptureRequest? = null,
    val capturePlaybackAudio: Boolean = false,
    val subscription: BroadcastSubscription? = null
) {
    override fun toString(): String = "BroadcastConfiguration(provider=$provider, draft=$protocolDraft, " +
        "relay=${RelayUrl.maskJwt(relayUrl)}, path=$streamPath, codec=$codec, fps=$framerate)"
}

internal class BroadcastSubscription(
    private val url: String, private val path: String, private val provider: RelayProvider, private val expectAudio: Boolean
) {
    fun verify(verifier: SubscriberVerifier) = verifier.verify(url, path, provider, expectAudio)
}
