package com.sangege.moqpublisher

/**
 * The update managers run independently from the Compose UI. Keep the rule for
 * showing an on-screen reminder pure so it can be tested without an Android
 * device and so a deferred core update is not silently lost while streaming.
 */
internal enum class CoreUpdateReminderKind { READY, DEFERRED, INCOMPATIBLE }

internal data class UpdateReminder(
    val appVersion: String? = null,
    val appReady: Boolean = false,
    val coreVersion: String? = null,
    val coreKind: CoreUpdateReminderKind = CoreUpdateReminderKind.READY
) {
    val hasAppUpdate: Boolean get() = appVersion != null
    val hasCoreUpdate: Boolean get() = coreVersion != null
    val appKey: String? get() = appVersion?.let { "$it:${if (appReady) "ready" else "available"}" }
    val coreKey: String? get() = coreVersion?.let { "$it:$coreKind" }

    fun withoutDismissed(appKey: String?, coreKey: String?): UpdateReminder? = copy(
        appVersion = appVersion.takeUnless { this.appKey == appKey },
        coreVersion = coreVersion.takeUnless { this.coreKey == coreKey }
    ).takeIf { it.hasAppUpdate || it.hasCoreUpdate }
}

internal fun updateReminderFor(app: AppUpdateState, core: CoreState): UpdateReminder? {
    val appVersion = app.latestVersion?.takeIf { AppUpdatePolicy.validVersion(it) &&
        AppUpdatePolicy.validVersion(app.currentVersion) &&
        AppUpdatePolicy.compare(it, app.currentVersion) > 0 &&
        app.phase in setOf(AppUpdatePhase.AVAILABLE, AppUpdatePhase.READY) }
    val appReady = appVersion != null && app.ready

    val pendingVersion = core.pendingVersion?.takeIf(CoreUpdatePolicy::validVersion)
    val coreKind = when {
        pendingVersion != null -> CoreUpdateReminderKind.READY
        core.notice == CoreNotice.DEFERRED -> CoreUpdateReminderKind.DEFERRED
        core.notice == CoreNotice.INCOMPATIBLE -> CoreUpdateReminderKind.INCOMPATIBLE
        else -> null
    }
    val coreVersion = when (coreKind) {
        CoreUpdateReminderKind.READY -> pendingVersion
        CoreUpdateReminderKind.DEFERRED, CoreUpdateReminderKind.INCOMPATIBLE -> core.latestVersion
        else -> null
    }?.takeIf { version ->
        CoreUpdatePolicy.validVersion(version) &&
            core.activeVersion != null && CoreUpdatePolicy.validVersion(core.activeVersion) &&
            CoreUpdatePolicy.compare(version, core.activeVersion) > 0
    }

    return UpdateReminder(
        appVersion = appVersion,
        appReady = appReady,
        coreVersion = coreVersion,
        coreKind = coreKind ?: CoreUpdateReminderKind.READY
    ).takeIf { it.hasAppUpdate || it.hasCoreUpdate }
}
