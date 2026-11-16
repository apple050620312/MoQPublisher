package com.sangege.moqpublisher

import dev.moq.VideoCodec

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
    val bitrateBps: Long,
    val adaptiveBitrate: Boolean,
    val provider: RelayProvider = RelayProvider.MOQ_PRO,
    val protocolDraft: Int? = null
) {
    override fun toString(): String = "BroadcastConfiguration(provider=$provider, draft=$protocolDraft, " +
        "relay=${RelayUrl.maskJwt(relayUrl)}, path=$streamPath, codec=$codec, fps=$framerate)"
}
