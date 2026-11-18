package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastPreflightTest {
    private val ready = BroadcastPreflightInput(
        cameraPermission = true,
        microphonePermission = true,
        notificationPermission = true,
        audioEnabled = true,
        cameraAvailable = true,
        audioAvailable = true,
        encoderAvailable = true,
        resolutionFpsSupported = true,
        relayConfigured = true,
        publishTokenConfigured = true,
        subscribeTokenConfigured = true,
        subscribeTokenRequired = true,
        networkAvailable = true,
        moqCoreReady = true,
        batteryPercent = 80
    )

    @Test
    fun readyInputHasNoErrors() {
        val report = BroadcastPreflightPolicy.evaluate(ready)
        assertTrue(report.canStart)
        assertTrue(report.errors.isEmpty())
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun permissionsCoreAndEncoderBlockStart() {
        val report = BroadcastPreflightPolicy.evaluate(ready.copy(
            cameraPermission = false,
            moqCoreReady = false,
            encoderAvailable = false
        ))
        assertFalse(report.canStart)
        assertEquals(3, report.errors.size)
    }

    @Test
    fun notificationNetworkBatteryAndThermalAreWarnings() {
        val report = BroadcastPreflightPolicy.evaluate(ready.copy(
            notificationPermission = false,
            networkAvailable = false,
            batteryPercent = 5,
            thermalWarning = true
        ))
        assertTrue(report.canStart)
        assertEquals(4, report.warnings.size)
    }

    @Test
    fun disabledAudioDoesNotRequireMicrophoneOrSubscribeToken() {
        val report = BroadcastPreflightPolicy.evaluate(ready.copy(
            audioEnabled = false,
            microphonePermission = false,
            subscribeTokenRequired = false,
            subscribeTokenConfigured = false
        ))
        assertTrue(report.canStart)
    }
}
