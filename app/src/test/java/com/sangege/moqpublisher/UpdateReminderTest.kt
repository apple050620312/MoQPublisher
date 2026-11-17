package com.sangege.moqpublisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReminderTest {
    @Test
    fun appReleaseUpdateIsShownAndReadyStateHasDistinctActionKey() {
        val available = updateReminderFor(
            AppUpdateState(phase = AppUpdatePhase.AVAILABLE, currentVersion = "1.0.18", latestVersion = "1.0.19"),
            CoreState()
        )
        assertEquals("1.0.19", available?.appVersion)
        assertFalse(available!!.appReady)
        assertEquals("1.0.19:available", available.appKey)

        val ready = updateReminderFor(
            AppUpdateState(phase = AppUpdatePhase.READY, currentVersion = "1.0.18", latestVersion = "1.0.19", downloadedPath = "/tmp/update.apk"),
            CoreState()
        )
        assertTrue(ready!!.appReady)
        assertEquals("1.0.19:ready", ready.appKey)
    }

    @Test
    fun pendingCoreUpdateIsShownAfterDownload() {
        val reminder = updateReminderFor(
            AppUpdateState(),
            CoreState(activeVersion = "0.4.26", pendingVersion = "0.4.27", notice = CoreNotice.UPDATE_READY)
        )
        assertEquals("0.4.27", reminder?.coreVersion)
        assertEquals(CoreUpdateReminderKind.READY, reminder!!.coreKind)
        assertEquals("0.4.27:READY", reminder.coreKey)
    }

    @Test
    fun deferredCoreUpdateIsShownWhileStreaming() {
        val reminder = updateReminderFor(
            AppUpdateState(),
            CoreState(activeVersion = "0.4.26", latestVersion = "0.4.27", notice = CoreNotice.DEFERRED)
        )
        assertEquals("0.4.27", reminder?.coreVersion)
        assertEquals(CoreUpdateReminderKind.DEFERRED, reminder!!.coreKind)
    }

    @Test
    fun sameCoreAndFirstInstallDoNotTriggerReminder() {
        assertNull(updateReminderFor(
            AppUpdateState(),
            CoreState(activeVersion = "0.4.27", latestVersion = "0.4.27", notice = CoreNotice.DEFERRED)
        ))
        assertNull(updateReminderFor(
            AppUpdateState(),
            CoreState(latestVersion = "0.4.27", notice = CoreNotice.DEFERRED)
        ))
    }

    @Test
    fun incompatibleNewCoreStillNotifiesTheUserToUpdateTheApp() {
        val reminder = updateReminderFor(AppUpdateState(),
            CoreState(activeVersion = "0.4.26", latestVersion = "0.5.0", notice = CoreNotice.INCOMPATIBLE))
        assertEquals("0.5.0", reminder?.coreVersion)
        assertEquals(CoreUpdateReminderKind.INCOMPATIBLE, reminder?.coreKind)
    }

    @Test
    fun pendingCoreIsStillVisibleWhenARecheckIsOfflineOrInProgress() {
        for (notice in listOf(CoreNotice.NONE, CoreNotice.OFFLINE)) {
            val reminder = updateReminderFor(AppUpdateState(), CoreState(
                phase = CorePhase.CHECKING, activeVersion = "0.4.26", pendingVersion = "0.4.27", notice = notice))
            assertEquals("0.4.27", reminder?.coreVersion)
            assertEquals(CoreUpdateReminderKind.READY, reminder?.coreKind)
        }
    }

    @Test
    fun dismissingOneUpdateDoesNotHideAnotherAndDownloadCompletionRemindsAgain() {
        val app = AppUpdateState(phase = AppUpdatePhase.AVAILABLE, currentVersion = "1.0.18", latestVersion = "1.0.19")
        val core = CoreState(activeVersion = "0.4.26", pendingVersion = "0.4.27", notice = CoreNotice.UPDATE_READY)
        val reminder = updateReminderFor(app, core)!!
        assertNull(reminder.withoutDismissed(reminder.appKey, reminder.coreKey))
        val coreOnly = reminder.withoutDismissed(reminder.appKey, null)!!
        assertFalse(coreOnly.hasAppUpdate)
        assertTrue(coreOnly.hasCoreUpdate)
        val ready = updateReminderFor(app.copy(phase = AppUpdatePhase.READY, downloadedPath = "/tmp/update.apk"), core)!!
        val next = ready.withoutDismissed(reminder.appKey, reminder.coreKey)!!
        assertTrue(next.hasAppUpdate)
        assertTrue(next.appReady)
        assertFalse(next.hasCoreUpdate)
    }

    @Test
    fun noReminderForCurrentOrMalformedAppVersions() {
        for (version in listOf("1.0.18", "1.0.17", "not-a-version")) {
            assertNull(updateReminderFor(AppUpdateState(
                phase = AppUpdatePhase.AVAILABLE, currentVersion = "1.0.18", latestVersion = version), CoreState()))
        }
    }
}
