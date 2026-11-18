package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class VideoBitrateControlTest {
    private fun control(target: Long, audio: Long = 0L) = VideoBitrateControl(
        VideoBitrateConfiguration(VideoBitrateSettings.MIN_BPS, target, target, adaptive = true), audio
    )

    @Test
    fun aRecoveredLinkReachesTheEntireConfiguredTargetWithoutGettingStuck() {
        val control = control(8_000_000L, 64_000L)
        assertEquals(736_000L, control.update(1_000_000uL) {})
        var previous = control.currentBps
        repeat(50) {
            control.update(100_000_000uL) {}
            assertTrue(control.currentBps >= previous)
            assertTrue(control.currentBps <= 8_000_000L)
            previous = control.currentBps
        }
        assertEquals(8_000_000L, control.currentBps)
        assertNull(control.update(100_000_000uL) {})
    }

    @Test
    fun smallCapacityJitterDoesNotRepeatedlyLowerTheEncoderRate() {
        val control = control(8_000_000L)
        assertNull(control.update(9_500_000uL) {}) // budget 7.6 Mbps, within downward deadband
        assertEquals(8_000_000L, control.currentBps)
        assertEquals(7_000_000L, control.update(8_750_000uL) {})
    }

    @Test
    fun leavesHeadroomForAudioAndTransportWhenUplinkSlows() {
        val control = control(2_500_000L, 64_000L)
        assertEquals(736_000L, control.update(1_000_000uL) {})
        assertNull(control.update(1_000_000uL) {})
    }

    @Test
    fun capacityGrowthCannotExceedTheEncoderCeiling() {
        val control = control(2_500_000L)
        assertNull(control.update(50_000_000uL) {})
        assertNull(control.update(ULong.MAX_VALUE) {})
    }

    @Test
    fun recoveryIsGradualAndMissingEstimatesKeepTheRate() {
        val control = control(2_500_000L)
        assertEquals(800_000L, control.update(1_000_000uL) {})
        assertNull(control.update(null) {})
        assertEquals(880_000L, control.update(50_000_000uL) {})
    }

    @Test
    fun tinyCapacityDoesNotRequestAnInvalidZeroBitrate() {
        val control = control(2_500_000L, 64_000L)
        assertEquals(100_000L, control.update(0uL) {})
    }

    @Test
    fun changingTheCeilingBoundsFutureAdaptationWithoutExceedingIt() {
        val control = control(8_000_000L)
        var acceptedRate = 8_000_000L
        control.changeSettings(control.settings.copy(targetBps = 2_000_000, maximumBps = 2_000_000)) {
            acceptedRate = it
        }
        assertEquals(2_000_000L, acceptedRate)
        repeat(20) { control.update(100_000_000uL) { fail("Encoder should already be at the ceiling") } }
        assertEquals(2_000_000L, control.currentBps)
    }

    @Test
    fun customMinimumAndMaximumBoundAbrWithoutBreakingTheTarget() {
        val control = VideoBitrateControl(VideoBitrateConfiguration(500_000, 2_000_000, 4_000_000, true), 0L)
        assertEquals(2_000_000L, control.currentBps)
        repeat(10) { control.update(100_000_000uL) {} }
        assertEquals(4_000_000L, control.currentBps)
        assertEquals(500_000L, control.update(0uL) {})
        assertEquals(500_000L, control.currentBps)
        control.changeSettings(control.settings.copy(minimumBps = 750_000, maximumBps = 3_000_000)) {
            assertEquals(750_000L, it)
        }
        assertEquals(750_000L, control.currentBps)
    }

    @Test fun oldQueueReducesBitrateWithoutCapacityEstimateAndRespectsTheMinimum() {
        val control = VideoBitrateControl(VideoBitrateConfiguration(750_000, 1_000_000, 2_000_000, true), 0L)
        assertEquals(750_000L, control.update(null, queueAgeMs = 250) {})
        assertNull(control.update(50_000_000uL, queueDepth = 4, rttMs = 500) { fail("Must stay at the minimum") })
        assertEquals(825_000L, control.update(50_000_000uL) {})
    }

    @Test fun steadyHighRttLeavesHeadroomWithoutRepeatedlyReducingTheBitrate() {
        val control = control(8_000_000L)
        assertEquals(5_440_000L, control.update(8_000_000uL, rttMs = 550) {})
        repeat(10) { assertNull(control.update(8_000_000uL, rttMs = 550) {}) }
        assertNull(control.update(null, rttMs = 550) {})
        assertEquals(5_440_000L, control.currentBps)
    }

    @Test fun encoderRejectionPreservesTheAcceptedRateAndConfiguration() {
        val control = control(8_000_000)
        val original = control.settings
        val next = original.copy(targetBps = 4_000_000, maximumBps = 4_000_000, adaptive = false)
        assertThrows(IllegalStateException::class.java) {
            control.changeSettings(next) { error("codec rejected the new rate") }
        }
        assertEquals(original, control.settings)
        assertEquals(8_000_000L, control.currentBps)
        assertEquals(RuntimeBitrateSupport.REJECTED, control.runtimeSupport)
        assertNull(control.update(100_000uL) { fail("Automatic updates must wait for an encoder restart") })
        control.encoderOpened()
        assertEquals(RuntimeBitrateSupport.UNKNOWN, control.runtimeSupport)
        control.changeSettings(next) { assertEquals(4_000_000L, it) }
        assertEquals(next, control.settings)
        assertEquals(4_000_000L, control.currentBps)
        assertEquals(RuntimeBitrateSupport.ACCEPTED, control.runtimeSupport)
        assertNull(control.update(100_000uL) { fail("Manual rate must stay fixed") })
    }
}
