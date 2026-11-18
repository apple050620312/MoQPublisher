package com.sangege.moqpublisher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

enum class DeviceHealthIssue { LOW_BATTERY, THERMAL, MEMORY_PRESSURE, CPU_PRESSURE }
enum class DeviceQualityRecommendation { NONE, REDUCE_BITRATE }

data class DeviceHealthSnapshot(
    val batteryPercent: Int? = null,
    val thermalStatus: Int? = null,
    val memoryAvailablePercent: Int? = null,
    val powerSaveMode: Boolean = false,
    val issues: Set<DeviceHealthIssue> = emptySet(),
    val recommendation: DeviceQualityRecommendation = DeviceQualityRecommendation.NONE,
    val charging: Boolean? = null,
    val processCpuPercent: Double? = null,
    val encoderFps: Double? = null
)

internal object DeviceHealthPolicy {
    fun evaluate(
        batteryPercent: Int?,
        thermalStatus: Int?,
        memoryAvailablePercent: Int?,
        powerSaveMode: Boolean,
        charging: Boolean? = null,
        processCpuPercent: Double? = null,
        cpuCount: Int = 1,
        encoderFps: Double? = null,
        targetFps: Int = 0
    ): DeviceHealthSnapshot {
        val issues = linkedSetOf<DeviceHealthIssue>()
        if (batteryPercent != null && batteryPercent < 20) issues += DeviceHealthIssue.LOW_BATTERY
        if (thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) issues += DeviceHealthIssue.THERMAL
        if (memoryAvailablePercent != null && memoryAvailablePercent < 10) issues += DeviceHealthIssue.MEMORY_PRESSURE
        if (processCpuPercent != null && processCpuPercent >= cpuCount.coerceAtLeast(1) * 80 &&
            encoderFps != null && targetFps > 0 && encoderFps < targetFps * 0.7) issues += DeviceHealthIssue.CPU_PRESSURE
        val recommendation = if (issues.any { it != DeviceHealthIssue.LOW_BATTERY })
            DeviceQualityRecommendation.REDUCE_BITRATE else DeviceQualityRecommendation.NONE
        return DeviceHealthSnapshot(
            batteryPercent, thermalStatus, memoryAvailablePercent, powerSaveMode, issues, recommendation,
            charging, processCpuPercent, encoderFps
        )
    }
}

internal class DeviceHealthMonitor(context: Context) {
    private val context = context.applicationContext

    fun sample(processCpuPercent: Double?, encoderFps: Double?, targetFps: Int): DeviceHealthSnapshot {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null
        val memory = runCatching {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            if (info.totalMem > 0) (info.availMem * 100 / info.totalMem).toInt() else null
        }.getOrNull()
        return DeviceHealthPolicy.evaluate(
            percent, thermal, memory, runCatching { power.isPowerSaveMode }.getOrDefault(false),
            charging, processCpuPercent, Runtime.getRuntime().availableProcessors(), encoderFps, targetFps
        )
    }
}
