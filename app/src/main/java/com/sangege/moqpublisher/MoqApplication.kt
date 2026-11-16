package com.sangege.moqpublisher

import android.app.Application

class MoqApplication : Application() {
    val core by lazy { MoqCoreManager(this) }
    val appUpdates by lazy { AppUpdateManager(this) }
    internal val session by lazy { BroadcastSession(this) }
    override fun onCreate() {
        super.onCreate()
        core.checkForUpdates()
        appUpdates.checkForUpdates()
    }
}
