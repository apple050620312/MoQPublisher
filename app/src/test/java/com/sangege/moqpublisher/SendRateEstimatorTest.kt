package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendRateEstimatorTest {
    @Test
    fun constantUploadDoesNotGrowWithConnectionAge() {
        val estimator = SendRateEstimator()
        assertNull(estimator.sample(0uL, 0L, 1uL))

        // Two minutes at 2.5 Mbps must still read 2500 kbps, even as Sent grows.
        for (second in 1L..120L) {
            assertEquals(
                2_500L,
                estimator.sample((312_500L * second).toULong(), second * 1_000_000_000L, 1uL)
            )
        }
    }

    @Test
    fun usesActualElapsedTimeWhenPollingIsDelayed() {
        val estimator = SendRateEstimator()
        estimator.sample(123uL, 7_000_000_000L, 1uL)
        assertEquals(2_500L, estimator.sample(625_123uL, 9_000_000_000L, 1uL))
        assertEquals(2_000L, estimator.sample(687_623uL, 9_250_000_000L, 1uL))
    }

    @Test
    fun idleConnectionReportsZero() {
        val estimator = SendRateEstimator()
        estimator.sample(10_000uL, 0L, 1uL)
        assertEquals(0L, estimator.sample(10_000uL, 1_000_000_000L, 1uL))
    }

    @Test
    fun missingCountersInvalidateTheBaseline() {
        val estimator = SendRateEstimator()
        estimator.sample(0uL, 0L, 1uL)
        assertNull(estimator.sample(null, 1_000_000_000L, 1uL))
        assertNull(estimator.sample(9_000_000uL, 2_000_000_000L, 1uL))
        assertEquals(2_500L, estimator.sample(9_312_500uL, 3_000_000_000L, 1uL))
    }

    @Test
    fun reconnectInvalidatesEvenAnIncreasingCounter() {
        val estimator = SendRateEstimator()
        estimator.sample(100_000uL, 0L, 1uL)
        assertNull(estimator.sample(900_000uL, 1_000_000_000L, 2uL))
        assertEquals(2_500L, estimator.sample(1_212_500uL, 2_000_000_000L, 2uL))
    }

    @Test
    fun counterResetDoesNotUnderflow() {
        val estimator = SendRateEstimator()
        estimator.sample(9_000_000uL, 0L, 1uL)
        assertNull(estimator.sample(0uL, 1_000_000_000L, 1uL))
        assertEquals(2_500L, estimator.sample(312_500uL, 2_000_000_000L, 1uL))
    }

    @Test
    fun unsignedTotalsRetainSmallDeltas() {
        val estimator = SendRateEstimator()
        estimator.sample(ULong.MAX_VALUE - 312_500uL, 0L, 1uL)
        assertEquals(2_500L, estimator.sample(ULong.MAX_VALUE, 1_000_000_000L, 1uL))
    }

    @Test
    fun rejectsNonPositiveSampleIntervals() {
        val estimator = SendRateEstimator()
        estimator.sample(0uL, 1_000_000_000L, 1uL)
        assertNull(estimator.sample(100uL, 1_000_000_000L, 1uL))
        assertNull(estimator.sample(200uL, 500_000_000L, 1uL))
        assertEquals(2_500L, estimator.sample(312_700uL, 1_500_000_000L, 1uL))
    }
}
