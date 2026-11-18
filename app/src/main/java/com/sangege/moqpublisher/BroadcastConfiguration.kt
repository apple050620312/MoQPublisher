package com.sangege.moqpublisher

import android.util.Size
import dev.moq.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class VideoMode(val size: Size, val fps: Int)

internal class LiveVideoModeChange {
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
    val provider: RelayProvider = RelayProvider.MOQ_PRO,
    val protocolDraft: Int? = null
) {
    override fun toString(): String = "BroadcastConfiguration(provider=$provider, draft=$protocolDraft, " +
        "relay=${RelayUrl.maskJwt(relayUrl)}, path=$streamPath, codec=$codec, fps=$framerate)"
}
