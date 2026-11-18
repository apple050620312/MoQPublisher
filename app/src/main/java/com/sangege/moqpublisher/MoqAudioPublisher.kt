package com.sangege.moqpublisher

import dev.moq.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uniffi.moq.MoqContainer

/**
 * Keep the bundled Opus encoder, but publish several packets on each QUIC stream.
 * The encoder's per-packet groups stay in a local broadcast; only compressed Opus
 * crosses this bridge. Each packet is forwarded immediately, without waiting for
 * the 200 ms group to finish. This also preserves the encoder's real OpusHead,
 * including pre-skip, rather than inventing codec initialization bytes.
 *
 * All writes and close belong to the audio publisher thread.
 */
internal class MoqAudioPublisher private constructor(
    private val encodingBroadcast: BroadcastProducer,
    private val source: BroadcastConsumer,
    private val encoder: AudioProducer,
    private val packets: MediaConsumer,
    private val output: MediaProducer
) : AutoCloseable {
    data class Published(val bytes: Int, val groupClosed: Boolean)

    private var packetsInGroup = 0
    private var closed = false

    fun write(packet: EncodedAudioPacket): Published = runBlocking { writeSuspending(packet) }

    suspend fun writeSuspending(packet: EncodedAudioPacket): Published {
        check(!closed)
        if (packet.resetEpoch) {
            encoder.resetEpoch()
            output.discontinuity()
            packetsInGroup = 0
        }
        encoder.write(AudioFrame(packet.timestampUs.toULong(), packet.data))
        // One complete 20 ms PCM input always produces one Opus packet. A local
        // subscription has no network wait; bound the wait if the core fails.
        val frame = withTimeout(READ_TIMEOUT_MS) {
            var frame = checkNotNull(packets.next()) { "Local Opus track ended" }
            // The byte-oriented FFI consumer exposes the epoch's empty marker.
            // The outgoing track has already received its own discontinuity.
            if (frame.payload.isEmpty() && packet.resetEpoch) {
                frame = checkNotNull(packets.next()) { "Local Opus track ended after reset" }
            }
            frame
        }
        check(frame.payload.isNotEmpty()) { "Empty Opus packet" }
        output.writeFrame(Frame(payload = frame.payload, timestampUs = frame.timestampUs))
        output.flush(frame.timestampUs)
        val cut = ++packetsInGroup >= PACKETS_PER_GROUP
        if (cut) {
            output.cut()
            packetsInGroup = 0
        }
        return Published(frame.payload.size, cut)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { packets.cancel() }
        runCatching { packets.close() }
        runCatching { output.finish() }
        runCatching { output.close() }
        runCatching { encoder.finish() }
        runCatching { encoder.close() }
        runCatching { source.close() }
        runCatching { encodingBroadcast.end() }
        runCatching { encodingBroadcast.close() }
    }

    companion object {
        const val PACKETS_PER_GROUP = 10
        const val GROUP_DURATION_MS = PACKETS_PER_GROUP * 20
        private const val READ_TIMEOUT_MS = 1_000L

        fun open(
            broadcast: BroadcastProducer, channel: AudioChannel, bandwidth: Bandwidth? = null,
            bitrateBps: Long = channel.bitrateBps, trackName: String = "mic"
        ): MoqAudioPublisher {
            val local = BroadcastProducer()
            var source: BroadcastConsumer? = null
            var encoder: AudioProducer? = null
            var packets: MediaConsumer? = null
            var output: MediaProducer? = null
            try {
                encoder = AudioCodec.opus().use { codec ->
                    local.encodeAudio(
                        name = trackName,
                        input = AudioEncoderInput(
                            format = AudioSampleFormat.S16,
                            sampleRate = AudioSettings.SAMPLE_RATE.toUInt(),
                            channels = channel.count.toUInt()
                        ),
                        output = AudioEncoderOutput(
                            codec = codec,
                            sampleRate = AudioSettings.SAMPLE_RATE.toUInt(),
                            channels = channel.count.toUInt(),
                            bitrate = bitrateBps.toUInt(),
                            frameDurationUs = 20_000u
                        ),
                        bandwidth = bandwidth
                    )
                }
                val activeSource = local.consume().also { source = it }
                val (head, subscription) = runBlocking {
                    withTimeout(READ_TIMEOUT_MS) {
                        val config = activeSource.subscribeCatalog().use { catalog ->
                            checkNotNull(catalog.next()?.audio?.get(trackName)) { "Missing Opus catalog" }
                        }
                        check(config.codec == "opus" && config.channelCount == channel.count.toUInt())
                        checkNotNull(config.description) { "Missing OpusHead" } to activeSource.subscribeMedia(
                            name = trackName, container = MoqContainer.Legacy,
                            // Retain all local packets while the publisher reads one per write.
                            subscription = Subscription(maxAgeUs = 5_000_000u, groupStart = 0u)
                        )
                    }
                }
                packets = subscription
                output = broadcast.publishAudio(AudioInit(format = AudioFormat.OPUS, data = head, track = trackName))
                return MoqAudioPublisher(local, activeSource, encoder, subscription, output)
            } catch (error: Throwable) {
                runCatching { packets?.cancel() }
                runCatching { packets?.close() }
                runCatching { output?.finish() }
                runCatching { output?.close() }
                runCatching { encoder?.finish() }
                runCatching { encoder?.close() }
                runCatching { source?.close() }
                runCatching { local.end() }
                runCatching { local.close() }
                throw error
            }
        }
    }
}
