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
        hardwareSurfaceEncoderReported = true,
        resolutionFpsValid = true,
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
    fun permissionsAndCoreBlockStart() {
        val report = BroadcastPreflightPolicy.evaluate(ready.copy(
            cameraPermission = false,
            moqCoreReady = false
        ))
        assertFalse(report.canStart)
        assertEquals(listOf(PreflightCheckId.CAMERA_PERMISSION, PreflightCheckId.MOQ_CORE), report.errors.map { it.id })
    }

    @Test
    fun unreportedHardwareSurfaceSupportStillAllowsAnEncoderAttempt() {
        val report = BroadcastPreflightPolicy.evaluate(ready.copy(hardwareSurfaceEncoderReported = false))
        assertTrue(report.canStart)
        assertEquals(PreflightCheckId.ENCODER_CAPABILITY, report.warnings.single().id)
    }

    @Test
    fun invalidVideoSettingsStillBlockStart() {
        val report = BroadcastPreflightPolicy.evaluate(ready.copy(resolutionFpsValid = false))
        assertFalse(report.canStart)
        assertEquals(PreflightCheckId.RESOLUTION_FPS, report.errors.single().id)
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
