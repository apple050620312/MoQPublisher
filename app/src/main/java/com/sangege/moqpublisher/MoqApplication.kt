package com.sangege.moqpublisher

import android.app.Application

class MoqApplication : Application() {
    val core by lazy { MoqCoreManager(this) }
    internal val session by lazy { BroadcastSession(this) }
    override fun onCreate() {
        super.onCreate()
        core.checkForUpdates()
    }
}
