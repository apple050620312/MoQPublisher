package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AudioSettingsTest {
    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    @Test
    fun unityGainPreservesEverySignedSixteenBitSampleExactly() {
        val original = pcm(*(Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()).toList().toIntArray())
        val amplified = original.clone()
        AudioSettings.applyGain(amplified, 100)
        assertArrayEquals(original, amplified)
    }

    @Test
    fun zeroGainMutesBothInterleavedChannels() {
        val bytes = pcm(32767, -32768, 1, -1, 12345, -23145)
        AudioSettings.applyGain(bytes, 0)
        assertArrayEquals(ByteArray(bytes.size), bytes)
    }

    @Test
    fun attenuatesPositiveAndNegativeSamplesInLittleEndianOrder() {
        val bytes = pcm(2000, -2000, 100, -100, 0, -32768)
        AudioSettings.applyGain(bytes, 50)
        assertArrayEquals(pcm(1000, -1000, 50, -50, 0, -16384), bytes)
    }

    @Test
    fun amplifiesStereoIndependentlyAndClipsWithoutWrapping() {
        val bytes = pcm(20000, -20000, 1200, -1400, 32767, -32768)
        AudioSettings.applyGain(bytes, 200)
        assertArrayEquals(pcm(32767, -32768, 2400, -2800, 32767, -32768), bytes)
    }

    @Test
    fun gainOutsideRangeIsClamped() {
        val maximum = pcm(10000, -10000)
        AudioSettings.applyGain(maximum, Int.MAX_VALUE)
        assertArrayEquals(pcm(30000, -30000), maximum)
        val minimum = pcm(10000, -10000)
        AudioSettings.applyGain(minimum, -1)
        assertArrayEquals(pcm(0, 0), minimum)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPcmSample() {
        AudioSettings.applyGain(ByteArray(3), 150)
    }

    @Test
    fun bothChannelFormatsRepresentExactlyTwentyMilliseconds() {
        for (channel in AudioChannel.entries) {
            assertEquals(20, AudioSettings.frameBytes(channel) * 1000 / (AudioSettings.SAMPLE_RATE * channel.count * 2))
        }
        assertEquals(1920, AudioSettings.frameBytes(AudioChannel.MONO))
        assertEquals(3840, AudioSettings.frameBytes(AudioChannel.STEREO))
    }

    @Test
    fun unsupportedStereoPublishesTheActualMonoFormat() {
        val attempted = mutableListOf<AudioChannel>()
        var reported = false
        val capture = AudioSettings.openCapture(AudioChannel.STEREO, { channel ->
            attempted += channel
            if (channel == AudioChannel.STEREO) error("Unsupported hardware format")
            "mono recorder"
        }, { reported = true })
        assertEquals(listOf(AudioChannel.STEREO, AudioChannel.MONO), attempted)
        assertEquals(AudioChannel.MONO, capture.first)
        assertEquals("mono recorder", capture.second)
        assertTrue(reported)
    }

    @Test
    fun supportedStereoDoesNotOpenASecondRecorder() {
        val attempted = mutableListOf<AudioChannel>()
        val capture = AudioSettings.openCapture(AudioChannel.STEREO, { attempted += it; "stereo recorder" })
        assertEquals(AudioChannel.STEREO, capture.first)
        assertEquals(listOf(AudioChannel.STEREO), attempted)
    }

    @Test
    fun permissionRevocationAndCancellationDoNotRetryHardwareCapture() {
        for (failure in listOf(SecurityException("Permission revoked"), CancellationException("Stopped"))) {
            var attempts = 0
            try {
                AudioSettings.openCapture(AudioChannel.STEREO, { attempts++; throw failure })
                fail("Expected the original failure")
            } catch (actual: Exception) {
                assertSame(failure, actual)
                assertEquals(1, attempts)
            }
        }
    }

    @Test
    fun failedMonoFallbackPropagatesRatherThanLooping() {
        var attempts = 0
        try {
            AudioSettings.openCapture(AudioChannel.STEREO, { attempts++; error("No microphone") })
            fail("Expected capture failure")
        } catch (_: IllegalStateException) {
            assertEquals(2, attempts)
        }
    }
}
