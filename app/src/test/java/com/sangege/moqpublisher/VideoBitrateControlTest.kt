package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class VideoBitrateControlTest {
    @Test
    fun aRecoveredLinkReachesTheEntireConfiguredTargetWithoutGettingStuck() {
        val control = VideoBitrateControl(8_000_000L, 64_000L)
        assertEquals(736_000L, control.update(1_000_000uL))
        var previous = control.currentBps
        repeat(50) {
            control.update(100_000_000uL)
            assertTrue(control.currentBps >= previous)
            assertTrue(control.currentBps <= 8_000_000L)
            previous = control.currentBps
        }
        assertEquals(8_000_000L, control.currentBps)
        assertNull(control.update(100_000_000uL))
    }

    @Test
    fun smallCapacityJitterDoesNotRepeatedlyLowerTheEncoderRate() {
        val control = VideoBitrateControl(8_000_000L, 0L)
        assertNull(control.update(9_500_000uL)) // budget 7.6 Mbps, within downward deadband
        assertEquals(8_000_000L, control.currentBps)
        assertEquals(7_000_000L, control.update(8_750_000uL))
    }

    @Test
    fun leavesHeadroomForAudioAndTransportWhenUplinkSlows() {
        val control = VideoBitrateControl(2_500_000L, 64_000L)
        assertEquals(736_000L, control.update(1_000_000uL))
        assertNull(control.update(1_000_000uL))
    }

    @Test
    fun capacityGrowthCannotExceedTheEncoderCeiling() {
        val control = VideoBitrateControl(2_500_000L, 0L)
        assertNull(control.update(50_000_000uL))
        assertNull(control.update(ULong.MAX_VALUE))
    }

    @Test
    fun recoveryIsGradualAndMissingEstimatesKeepTheRate() {
        val control = VideoBitrateControl(2_500_000L, 0L)
        assertEquals(800_000L, control.update(1_000_000uL))
        assertNull(control.update(null))
        assertEquals(880_000L, control.update(50_000_000uL))
    }

    @Test
    fun tinyCapacityDoesNotRequestAnInvalidZeroBitrate() {
        val control = VideoBitrateControl(2_500_000L, 64_000L)
        assertEquals(100_000L, control.update(0uL))
    }
}
