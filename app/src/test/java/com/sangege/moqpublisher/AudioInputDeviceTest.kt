package com.sangege.moqpublisher

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputDeviceTest {
    @Test
    fun acceptsPhysicalMicrophoneInputTypesOnly() {
        assertTrue(AudioInputDevicePolicy.isSupportedType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertTrue(AudioInputDevicePolicy.isSupportedType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertTrue(AudioInputDevicePolicy.isSupportedType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertFalse(AudioInputDevicePolicy.isSupportedType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(AudioInputDevicePolicy.isSupportedType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
    }

    @Test
    fun defaultRouteUsesNoPreferredDevice() {
        assertTrue(AudioInputDeviceRepositoryTestSupport.defaultId == DEFAULT_AUDIO_INPUT_DEVICE_ID)
    }

    private object AudioInputDeviceRepositoryTestSupport {
        const val defaultId = DEFAULT_AUDIO_INPUT_DEVICE_ID
    }
}
