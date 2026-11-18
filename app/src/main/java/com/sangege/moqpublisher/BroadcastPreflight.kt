package com.sangege.moqpublisher

enum class PreflightSeverity { PASS, WARNING, ERROR }

enum class PreflightCheckId {
    CAMERA_PERMISSION,
    MICROPHONE_PERMISSION,
    NOTIFICATION_PERMISSION,
    CAMERA_AVAILABILITY,
    AUDIO_AVAILABILITY,
    ENCODER_CAPABILITY,
    RESOLUTION_FPS,
    RELAY_CONFIGURATION,
    PUBLISH_TOKEN,
    SUBSCRIBE_TOKEN,
    NETWORK,
    MOQ_CORE,
    BATTERY,
    THERMAL
}

data class PreflightCheck(
    val id: PreflightCheckId,
    val severity: PreflightSeverity,
    val detail: String
)

data class BroadcastPreflightReport(val checks: List<PreflightCheck>) {
    val errors: List<PreflightCheck> get() = checks.filter { it.severity == PreflightSeverity.ERROR }
    val warnings: List<PreflightCheck> get() = checks.filter { it.severity == PreflightSeverity.WARNING }
    val canStart: Boolean get() = errors.isEmpty()
}

data class BroadcastPreflightInput(
    val cameraPermission: Boolean,
    val microphonePermission: Boolean,
    val notificationPermission: Boolean,
    val audioEnabled: Boolean,
    val cameraAvailable: Boolean,
    val audioAvailable: Boolean,
    val hardwareSurfaceEncoderReported: Boolean,
    val resolutionFpsValid: Boolean,
    val relayConfigured: Boolean,
    val publishTokenConfigured: Boolean,
    val subscribeTokenConfigured: Boolean,
    val subscribeTokenRequired: Boolean,
    val networkAvailable: Boolean,
    val moqCoreReady: Boolean,
    val batteryPercent: Int? = null,
    val thermalWarning: Boolean = false,
    val usesCamera: Boolean = true,
    val publishes: Boolean = true
)

/** Pure preflight policy so start checks stay deterministic and unit-testable. */
internal object BroadcastPreflightPolicy {
    fun evaluate(input: BroadcastPreflightInput): BroadcastPreflightReport {
        val checks = mutableListOf<PreflightCheck>()
        if (input.usesCamera) checks += check(PreflightCheckId.CAMERA_PERMISSION, input.cameraPermission,
            "Camera permission is granted", "Camera permission is required")
        checks += check(PreflightCheckId.MICROPHONE_PERMISSION, !input.audioEnabled || input.microphonePermission,
            "Microphone permission is ready", "Microphone permission is required when audio is enabled")
        checks += check(PreflightCheckId.NOTIFICATION_PERMISSION, input.notificationPermission,
            "Broadcast notification permission is granted", "Notifications are disabled. Background stream controls may be hidden",
            warningWhenFalse = true)
        if (input.usesCamera) checks += check(PreflightCheckId.CAMERA_AVAILABILITY, input.cameraAvailable,
            "A camera is available", "No camera is available")
        checks += check(PreflightCheckId.AUDIO_AVAILABILITY, !input.audioEnabled || input.audioAvailable,
            "An audio input is available", "No compatible microphone input is available")
        checks += check(PreflightCheckId.ENCODER_CAPABILITY, input.hardwareSurfaceEncoderReported,
            "Android reports a hardware surface encoder for this codec",
            "Android did not report a hardware surface encoder for this codec. The app will try to configure it",
            warningWhenFalse = true)
        checks += check(PreflightCheckId.RESOLUTION_FPS, input.resolutionFpsValid,
            "The requested resolution and FPS are valid", "The requested resolution or FPS is invalid")
        if (input.publishes) {
            checks += check(PreflightCheckId.RELAY_CONFIGURATION, input.relayConfigured,
                "Relay settings are valid", "Relay settings need correction")
            checks += check(PreflightCheckId.PUBLISH_TOKEN, input.publishTokenConfigured,
                "Publish authentication is configured", "A valid Publish token is required")
            checks += if (!input.subscribeTokenRequired) {
                PreflightCheck(PreflightCheckId.SUBSCRIBE_TOKEN, PreflightSeverity.PASS,
                    "Subscribe authentication is optional for publishing")
            } else check(
                PreflightCheckId.SUBSCRIBE_TOKEN,
                input.subscribeTokenConfigured,
                "Subscribe authentication is ready for remote verification",
                "Remote verification needs a separate Subscribe token"
            )
            checks += check(PreflightCheckId.NETWORK, input.networkAvailable,
                "A network is available", "No active network is available. The relay connection may wait for recovery",
                warningWhenFalse = true)
            checks += check(PreflightCheckId.MOQ_CORE, input.moqCoreReady,
                "The MoQ core is ready", "The MoQ core must finish preparing before broadcast")
        }
        checks += batteryCheck(input.batteryPercent)
        checks += if (input.thermalWarning) PreflightCheck(
            PreflightCheckId.THERMAL, PreflightSeverity.WARNING, "The device reports thermal pressure"
        ) else PreflightCheck(PreflightCheckId.THERMAL, PreflightSeverity.PASS, "No thermal warning is reported")
        return BroadcastPreflightReport(checks)
    }

    private fun check(
        id: PreflightCheckId,
        passed: Boolean,
        passDetail: String,
        errorDetail: String,
        warningWhenFalse: Boolean = false
    ): PreflightCheck = PreflightCheck(
        id,
        when {
            passed -> PreflightSeverity.PASS
            warningWhenFalse -> PreflightSeverity.WARNING
            else -> PreflightSeverity.ERROR
        },
        if (passed) passDetail else errorDetail
    )

    private fun batteryCheck(percent: Int?): PreflightCheck = when {
        percent == null -> PreflightCheck(PreflightCheckId.BATTERY, PreflightSeverity.PASS, "Battery level is unavailable")
        percent < 10 -> PreflightCheck(PreflightCheckId.BATTERY, PreflightSeverity.WARNING, "Battery level is below 10%")
        percent < 20 -> PreflightCheck(PreflightCheckId.BATTERY, PreflightSeverity.WARNING, "Battery level is below 20%")
        else -> PreflightCheck(PreflightCheckId.BATTERY, PreflightSeverity.PASS, "Battery level is sufficient")
    }
}
