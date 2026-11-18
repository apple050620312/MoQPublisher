package com.sangege.moqpublisher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

enum class DeviceHealthIssue { LOW_BATTERY, THERMAL, MEMORY_PRESSURE }

data class DeviceHealthSnapshot(
    val batteryPercent: Int? = null,
    val thermalStatus: Int? = null,
    val memoryAvailablePercent: Int? = null,
    val powerSaveMode: Boolean = false,
    val issues: Set<DeviceHealthIssue> = emptySet()
)

internal object DeviceHealthPolicy {
    fun evaluate(
        batteryPercent: Int?,
        thermalStatus: Int?,
        memoryAvailablePercent: Int?,
        powerSaveMode: Boolean
    ): DeviceHealthSnapshot {
        val issues = linkedSetOf<DeviceHealthIssue>()
        if (batteryPercent != null && batteryPercent < 20) issues += DeviceHealthIssue.LOW_BATTERY
        if (thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) issues += DeviceHealthIssue.THERMAL
        if (memoryAvailablePercent != null && memoryAvailablePercent < 10) issues += DeviceHealthIssue.MEMORY_PRESSURE
        return DeviceHealthSnapshot(batteryPercent, thermalStatus, memoryAvailablePercent, powerSaveMode, issues)
    }
}

internal class DeviceHealthMonitor(context: Context) {
    private val context = context.applicationContext

    fun sample(): DeviceHealthSnapshot {
        val battery = runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) level * 100 / scale else null
        }.getOrNull()
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null
        val memory = runCatching {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            if (info.totalMem > 0) (info.availMem * 100 / info.totalMem).toInt() else null
        }.getOrNull()
        return DeviceHealthPolicy.evaluate(battery, thermal, memory,
            runCatching { power.isPowerSaveMode }.getOrDefault(false))
    }
}
