package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class AudioQualityTest {
    @Test fun absentAndInvalidPreferencesPreserveTheOldBitrate() {
        assertEquals(AudioQuality.STANDARD, AudioQuality.fromPreference(null))
        assertEquals(AudioQuality.STANDARD, AudioQuality.fromPreference("unknown"))
        for (channel in AudioChannel.entries) assertEquals(channel.bitrateBps, AudioQuality.STANDARD.bitrateBps(channel))
    }

    @Test fun everyQualityHasARealDistinctOpusBitrateWithinTheCodecRange() {
        for (channel in AudioChannel.entries) {
            val rates = AudioQuality.entries.map { it.bitrateBps(channel) }
            assertEquals(rates.sorted().distinct(), rates)
            assertTrue(rates.all { it in 6_000L..510_000L })
        }
    }

    @Test fun stereoFallbackUsesTheActualMonoBudgetAtEveryQuality() {
        for (quality in AudioQuality.entries) {
            val (actual, _) = AudioSettings.openCapture(AudioChannel.STEREO, { channel ->
                if (channel == AudioChannel.STEREO) error("Unavailable stereo") else Unit
            })
            assertEquals(AudioChannel.MONO, actual)
            assertEquals(quality.bitrateBps(AudioChannel.STEREO) / 2, quality.bitrateBps(actual))
        }
    }
}
