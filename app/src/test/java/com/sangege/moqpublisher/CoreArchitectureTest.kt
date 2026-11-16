package com.sangege.moqpublisher

import org.junit.Assert.*
import org.junit.Test

class CoreArchitectureTest {
    @Test fun followsTheRunningProcessOnArm64Devices() {
        val supported = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
        assertEquals(CoreArchitecture.ARM64, CoreArchitecture.select(supported, true))
        assertEquals(CoreArchitecture.ARM32, CoreArchitecture.select(supported, false))
    }

    @Test fun supportsModernArm64OnlyDevicesAndX64Systems() {
        assertEquals(CoreArchitecture.ARM64, CoreArchitecture.select(listOf("arm64-v8a"), true))
        assertEquals(CoreArchitecture.X86_64, CoreArchitecture.select(listOf("x86_64", "x86"), true))
        assertEquals(CoreArchitecture.ARM32, CoreArchitecture.select(listOf("armeabi-v7a", "armeabi"), false))
    }

    @Test fun honorsAndroidAbiPreferenceAndNeverLoadsTheWrongBitness() {
        assertEquals(CoreArchitecture.X86_64, CoreArchitecture.select(listOf("x86_64", "arm64-v8a"), true))
        assertNull(CoreArchitecture.select(listOf("x86_64"), false))
        assertNull(CoreArchitecture.select(listOf("arm64-v8a"), false))
        assertNull(CoreArchitecture.select(listOf("x86"), false))
        assertNull(CoreArchitecture.select(emptyList(), true))
    }
}
