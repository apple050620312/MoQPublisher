package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class VideoBitrateSettingsTest {
    @Test
    fun acceptsUserTargetsAboveTheOldTwoPointFiveMbpsCap() {
        assertEquals(8_000_000L, VideoBitrateSettings.parseKbps("8000"))
        assertEquals(40_000_000L, VideoBitrateSettings.parseKbps("40000"))
        assertEquals(160_000_000L, VideoBitrateSettings.parseKbps("160000"))
    }

    @Test
    fun parsesDecimalAndLocaleCommaWithoutBinaryRounding() {
        assertEquals(2_500_000L, VideoBitrateSettings.parseKbps(" 2500 "))
        assertEquals(6_250_500L, VideoBitrateSettings.parseKbps("6250,5"))
        assertEquals(100_000L, VideoBitrateSettings.parseKbps("100"))
        assertEquals(8_000_001L, VideoBitrateSettings.parseKbps("8000.001"))
    }

    @Test
    fun rejectsIncompleteNegativeAndNonNumericInputsBeforeStartingBroadcast() {
        for (value in listOf("", " ", "-8000", "0", "99.999", "8000.", "NaN", "Infinity", "1e9", "8000 Kbps", "1,2.3")) {
            assertNull(value, VideoBitrateSettings.parseKbps(value))
        }
    }

    @Test
    fun rejectsFractionsOfOneBitAndMediaCodecIntegerOverflow() {
        assertEquals(Int.MAX_VALUE.toLong(), VideoBitrateSettings.parseKbps("2147483.647"))
        assertNull(VideoBitrateSettings.parseKbps("2147483.648"))
        assertNull(VideoBitrateSettings.parseKbps("8000.0001"))
        assertNull(VideoBitrateSettings.parseKbps("999999999999999999999999999999"))
    }

    @Test
    fun migratesExistingMbpsWithoutChangingEncoderBitrate() {
        for ((old, expected) in listOf("8" to "8000", "2,5" to "2500", "0.1" to "100", "8.000001" to "8000.001", "2147.483647" to "2147483.647")) {
            assertEquals(expected, VideoBitrateSettings.migrateMbps(old))
            assertNotNull(VideoBitrateSettings.parseKbps(VideoBitrateSettings.migrateMbps(old)))
        }
    }

    @Test
    fun missingOrInvalidLegacyPreferenceUsesEightMbps() {
        for (old in listOf(null, "", "invalid", "0", "99999999999999999")) {
            assertEquals("8000", VideoBitrateSettings.migrateMbps(old))
        }
        assertEquals(VideoBitrateSettings.DEFAULT_BPS, VideoBitrateSettings.parseKbps(VideoBitrateSettings.DEFAULT_KBPS))
    }
}
