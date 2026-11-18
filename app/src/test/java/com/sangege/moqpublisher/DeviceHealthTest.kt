package com.sangege.moqpublisher

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthTest {
    @Test
    fun lowBatteryThermalAndMemoryPressureBecomeWarnings() {
        val snapshot = DeviceHealthPolicy.evaluate(15, PowerManager.THERMAL_STATUS_SEVERE, 5, true)
        assertEquals(setOf(DeviceHealthIssue.LOW_BATTERY, DeviceHealthIssue.THERMAL, DeviceHealthIssue.MEMORY_PRESSURE), snapshot.issues)
        assertTrue(snapshot.powerSaveMode)
    }

    @Test
    fun normalDeviceHasNoIssues() {
        assertTrue(DeviceHealthPolicy.evaluate(80, PowerManager.THERMAL_STATUS_NONE, 50, false).issues.isEmpty())
    }
}
